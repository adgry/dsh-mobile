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
            // The release notes print the digest for whoever downloads in a browser. Reading it
            // here is what makes the GitHub path verify its download at all — a manifest with a
            // blank sha256 is installed on trust.
            sha256 = sha256FromNotes(body),
            sizeBytes = apk.size,
            notes = notesWithoutSha256(body),
        )
    }
}

private val SHA256_WORD = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
private val SHA256_HEADING = Regex("""^\s*\**\s*sha-?256\s*\**\s*[:：]?\s*$""", RegexOption.IGNORE_CASE)
private val SHA256_INLINE =
    Regex("""^\s*\**\s*sha-?256\s*\**\s*[:：]\s*`?[0-9a-fA-F]{64}`?\s*$""", RegexOption.IGNORE_CASE)
private val SHA256_BARE = Regex("""^\s*`?[0-9a-fA-F]{64}`?\s*$""")

/** The first bare SHA-256 in a release body, lowercased. Blank when the notes carry none. */
internal fun sha256FromNotes(body: String): String =
    SHA256_WORD.find(body)?.value?.lowercase().orEmpty()

/**
 * The release body with its digest — and the heading or fence holding it — taken out, because the
 * update card is not where a 64-character hex string helps anyone; the app checks it instead.
 */
internal fun notesWithoutSha256(body: String): String {
    val lines = body.lines()
    val kept = mutableListOf<String>()

    fun dropTrailingHeading() {
        while (kept.isNotEmpty() && SHA256_HEADING.matches(kept.last())) kept.removeAt(kept.lastIndex)
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            val close = (i + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("```") }
            val inner = lines.subList(i + 1, close ?: lines.size)
            val onlyDigest = inner.any { it.isNotBlank() } &&
                inner.all { it.isBlank() || SHA256_BARE.matches(it) }
            if (onlyDigest) {
                dropTrailingHeading()
                i = (close ?: lines.lastIndex) + 1
                continue
            }
        }
        if (SHA256_INLINE.matches(line) || SHA256_BARE.matches(line)) {
            dropTrailingHeading()
            i++
            continue
        }
        kept += line
        i++
    }

    return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
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
