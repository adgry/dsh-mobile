package com.dshmobile.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * What the app looks for at the configured update URL.
 *
 * The native shape is this object. A GitHub `releases/latest` payload is also accepted and mapped
 * onto it, because that is where a sideloaded build most often lives.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val apkUrl: String = "",
    /** Lowercase hex. Optional, but a download is only trusted blindly without it. */
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val notes: String = "",
    val minSdk: Int = 0,
) {
    val isUsable: Boolean get() = apkUrl.isNotBlank() && (versionCode > 0L || versionName.isNotBlank())
}

@Serializable
internal data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L,
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    fun toManifest(): UpdateManifest? {
        val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        return UpdateManifest(
            versionName = tagName.removePrefix("v").ifBlank { name },
            apkUrl = apk.downloadUrl,
            sizeBytes = apk.size,
            notes = body,
        )
    }
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val checkedAt: Long) : UpdateStatus
    data class Available(val manifest: UpdateManifest) : UpdateStatus
    data class Downloading(
        val manifest: UpdateManifest,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : UpdateStatus {
        val fraction: Float
            get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data class Ready(val manifest: UpdateManifest, val file: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Reads either shape of update descriptor. Kept free of Android types so it can be tested directly.
 */
internal fun parseUpdateManifest(json: Json, body: String): UpdateManifest? {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    // A GitHub release payload is recognised by its tag, and mapped onto the native shape.
    if (root.containsKey("tag_name") && root.containsKey("assets")) {
        val release = runCatching { json.decodeFromString<GitHubRelease>(body) }.getOrNull()
        if (release != null && !release.draft) return release.toManifest()
        return null
    }
    return runCatching { json.decodeFromString<UpdateManifest>(body) }.getOrNull()
}

/**
 * Compares dotted version strings so 1.10.0 sorts above 1.9.0, which a plain string compare gets
 * backwards. Used when a manifest carries no versionCode (a GitHub tag, for instance).
 */
internal fun compareVersionNames(left: String, right: String): Int {
    fun parts(value: String) = value.trim().removePrefix("v")
        .split('.', '-', '_')
        .map { chunk -> chunk.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    val a = parts(left)
    val b = parts(right)
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}
