package com.dshmobile.app.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `native manifest parses`() {
        val manifest = parseUpdateManifest(
            json,
            """
            {"versionCode":7,"versionName":"1.4.0","apkUrl":"https://x/app.apk",
             "sha256":"AbCd","sizeBytes":1234,"minSdk":26,"notes":"修了几个问题"}
            """.trimIndent(),
        )!!
        assertEquals(7L, manifest.versionCode)
        assertEquals("1.4.0", manifest.versionName)
        assertEquals("https://x/app.apk", manifest.apkUrl)
        assertEquals(1234L, manifest.sizeBytes)
        assertTrue(manifest.isUsable)
    }

    @Test
    fun `a github release payload is mapped onto the native shape`() {
        val manifest = parseUpdateManifest(
            json,
            """
            {"tag_name":"v1.5.2","name":"1.5.2","body":"release notes","draft":false,
             "prerelease":false,
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                       {"name":"dsh-mobile-1.5.2.apk","browser_download_url":"https://x/a.apk","size":999}]}
            """.trimIndent(),
        )!!
        assertEquals("1.5.2", manifest.versionName)
        assertEquals("https://x/a.apk", manifest.apkUrl)
        assertEquals(999L, manifest.sizeBytes)
        assertEquals("release notes", manifest.notes)
    }

    @Test
    fun `a draft release is ignored`() {
        assertNull(
            parseUpdateManifest(
                json,
                """{"tag_name":"v9","draft":true,"assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]}""",
            ),
        )
    }

    @Test
    fun `a release with no apk asset is not usable`() {
        assertNull(
            parseUpdateManifest(
                json,
                """{"tag_name":"v9","draft":false,"assets":[{"name":"n.txt","browser_download_url":"https://x/n.txt"}]}""",
            ),
        )
    }

    @Test
    fun `garbage is rejected rather than throwing`() {
        assertNull(parseUpdateManifest(json, "not json"))
        assertNull(parseUpdateManifest(json, "[1,2,3]"))
    }

    @Test
    fun `a manifest with no apk url is not usable`() {
        val manifest = parseUpdateManifest(json, """{"versionCode":9,"versionName":"9.0"}""")!!
        assertFalse(manifest.isUsable)
    }

    /** A plain string compare puts 1.9.0 above 1.10.0; the dotted comparison must not. */
    @Test
    fun `version names compare numerically per segment`() {
        assertTrue(compareVersionNames("1.10.0", "1.9.0") > 0)
        assertEquals(0, compareVersionNames("1.2.0", "1.2.0"))
        assertTrue(compareVersionNames("1.2.1", "1.2.0") > 0)
        assertTrue(compareVersionNames("2.0.0", "1.99.99") > 0)
        assertTrue(compareVersionNames("1.2.0", "1.2.1") < 0)
    }

    @Test
    fun `a leading v and trailing suffixes do not confuse the comparison`() {
        assertTrue(compareVersionNames("v1.3.0", "1.2.9") > 0)
        assertEquals(0, compareVersionNames("1.2.0-beta1", "1.2.0"))
        assertEquals(0, compareVersionNames("1.2", "1.2.0"))
    }

    /**
     * The body here is verbatim what `publish-update.sh` writes and what v1.3.1 carries on GitHub.
     * Before the digest was read out of it, this whole path installed without verifying anything.
     */
    @Test
    fun `a github release digest is read out of the notes and dropped from them`() {
        val body = "DSH Mobile v1.3.1\\n\\n" +
            "安装包会被应用内的「检查更新」自动发现。\\n\\n" +
            "SHA-256:\\n" +
            "```\\n" +
            "DE80C9715772E8D821F09E082F0F38CA4A69B4952D5AC7BBD21CA5A7C7BFC159\\n" +
            "```\\n"
        val manifest = parseUpdateManifest(
            json,
            """{"tag_name":"v1.3.1","draft":false,"body":${'"'}$body${'"'},
                "assets":[{"name":"dsh-mobile-1.3.1.apk","browser_download_url":"https://x/a.apk","size":1866960}]}""",
        )!!

        assertEquals(
            "de80c9715772e8d821f09e082f0f38ca4a69b4952d5ac7bbd21ca5a7c7bfc159",
            manifest.sha256,
        )
        assertEquals(
            "DSH Mobile v1.3.1\n\n安装包会被应用内的「检查更新」自动发现。",
            manifest.notes,
        )
    }

    @Test
    fun `an inline or bare digest line is stripped too`() {
        val digest = "a".repeat(64)
        assertEquals(digest, sha256FromNotes("修了几个问题\nSHA-256: $digest"))
        assertEquals("修了几个问题", notesWithoutSha256("修了几个问题\nSHA-256: $digest"))
        assertEquals("修了几个问题", notesWithoutSha256("修了几个问题\nsha256:\n`$digest`"))
    }

    /** A code block that is not the digest belongs in the notes, and must survive. */
    @Test
    fun `notes keep code blocks that are not the digest`() {
        val notes = "用法：\n```\nscripts/publish-update.sh 1.4.0\n```"
        assertEquals(notes, notesWithoutSha256(notes))
        assertEquals("", sha256FromNotes(notes))
    }
}
