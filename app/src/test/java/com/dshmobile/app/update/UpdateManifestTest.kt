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
}
