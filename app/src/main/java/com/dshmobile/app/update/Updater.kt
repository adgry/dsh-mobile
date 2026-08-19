package com.dshmobile.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.dshmobile.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Self-update: fetch a small manifest, download the APK, verify it, hand it to the system installer.
 *
 * Two things this deliberately does *not* try to do. It never installs silently — Android only
 * allows that for a device owner, so the system's confirm dialog is always part of the flow. And it
 * cannot replace a build signed with a different key: an in-place update requires the same signing
 * certificate, which is exactly why this project keeps one stable keystore.
 */
class Updater(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var job: Job? = null

    /*
     * Only the newest request may publish. Without this, cancelling an in-flight check leaves the
     * status stuck on Checking — and a guard that skipped new checks while Checking then wedged the
     * updater permanently, with nothing on screen to explain it.
     */
    private var generation = 0

    private fun publish(gen: Int, status: UpdateStatus) {
        if (gen == generation) _status.value = status
    }

    val currentVersionName: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "?" }

    val currentVersionCode: Long
        // PackageInfo.longVersionCode is API 28; minSdk here is 26, so go through the compat shim
        // rather than crashing with NoSuchMethodError on Android 8.
        get() = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrElse { 0L }

    /** Whether the user has already allowed this app to install packages. */
    val canInstallPackages: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun dismiss() {
        job?.cancel()
        generation++
        _status.value = UpdateStatus.Idle
    }

    fun check(manual: Boolean) {
        val url = settings.current.updateUrl.trim()
        if (url.isEmpty()) {
            if (manual) _status.value = UpdateStatus.Failed("还没有填更新地址")
            return
        }
        // A download in flight outranks a check; anything else is superseded.
        if (_status.value is UpdateStatus.Downloading) return

        // An automatic check stays quiet and doesn't nag more than once a day.
        if (!manual) {
            val last = settings.current.lastUpdateCheck
            if (System.currentTimeMillis() - last < 20 * 60 * 60 * 1000L) return
        }

        job?.cancel()
        val gen = ++generation
        job = scope.launch {
            publish(gen, UpdateStatus.Checking)
            Log.i(TAG, "check start manual=$manual url=$url")
            val result = runCatching { fetchManifest(url) }
            settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }

            result.fold(
                onSuccess = { manifest ->
                    Log.i(TAG, "check ok manifest=$manifest current=$currentVersionCode")
                    when {
                        manifest == null || !manifest.isUsable ->
                            publish(gen, UpdateStatus.Failed("更新地址的内容看不懂"))
                        manifest.minSdk > Build.VERSION.SDK_INT ->
                            publish(
                                gen,
                                UpdateStatus.Failed(
                                    "新版本需要 Android API ${manifest.minSdk}，本机是 ${Build.VERSION.SDK_INT}",
                                ),
                            )
                        isNewer(manifest) -> publish(gen, UpdateStatus.Available(manifest))
                        else -> publish(gen, UpdateStatus.UpToDate(System.currentTimeMillis()))
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "check failed", error)
                    if (manual) {
                        publish(gen, UpdateStatus.Failed(error.message ?: "检查更新失败"))
                    } else {
                        publish(gen, UpdateStatus.Idle)
                    }
                },
            )
        }
    }

    fun download(manifest: UpdateManifest) {
        job?.cancel()
        val gen = ++generation
        job = scope.launch {
            publish(gen, UpdateStatus.Downloading(manifest, 0L, manifest.sizeBytes))
            val outcome = runCatching { downloadApk(manifest, gen) }
            outcome.fold(
                onSuccess = { file ->
                    Log.i(TAG, "downloaded ${file.length()} bytes to $file")
                    publish(gen, UpdateStatus.Ready(manifest, file))
                },
                onFailure = { error ->
                    Log.w(TAG, "download failed", error)
                    publish(gen, UpdateStatus.Failed(error.message ?: "下载失败"))
                },
            )
        }
    }

    /**
     * Hands the APK to the system installer. The confirm sheet is the system's; all this can do is
     * make sure the file is reachable through the app's FileProvider and the permission is in place.
     */
    fun install(file: File) {
        if (!canInstallPackages) {
            requestInstallPermission()
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            _status.value = UpdateStatus.Failed("无法交给安装器")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { _status.value = UpdateStatus.Failed("这台设备没有可用的安装器") }
    }

    /** Opens the system page where the user allows this app to install APKs. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    private companion object {
        const val TAG = "DshUpdater"
    }

    // ---------------------------------------------------------------- internals

    private fun isNewer(manifest: UpdateManifest): Boolean = when {
        manifest.versionCode > 0L -> manifest.versionCode > currentVersionCode
        manifest.versionName.isNotBlank() ->
            compareVersionNames(manifest.versionName, currentVersionName) > 0
        else -> false
    }

    private suspend fun fetchManifest(url: String): UpdateManifest? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("更新地址返回 HTTP ${response.code}")
            }
            parseUpdateManifest(json, body)
        }
    }

    private suspend fun downloadApk(manifest: UpdateManifest, gen: Int): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One file per version, and stale downloads are cleared so the cache can't grow forever.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "dsh-mobile-${manifest.versionName.ifBlank { manifest.versionCode.toString() }}.apk")

        val request = Request.Builder().url(manifest.apkUrl).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("下载没有内容")
            if (!response.isSuccessful) throw IOException("下载失败，HTTP ${response.code}")

            val total = body.contentLength().takeIf { it > 0 } ?: manifest.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastPublished = 0L

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        // Progress is a UI nicety; publishing every chunk would just churn.
                        val now = System.currentTimeMillis()
                        if (now - lastPublished >= 100) {
                            lastPublished = now
                            publish(gen, UpdateStatus.Downloading(manifest, read, total))
                        }
                    }
                }
            }
            publish(gen, UpdateStatus.Downloading(manifest, read, total))

            if (manifest.sha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(manifest.sha256.trim(), ignoreCase = true)) {
                    target.delete()
                    throw IOException("校验不通过，安装包可能损坏")
                }
            }
        }
        target
    }
}
