package com.jagdeep.autopilot.platform

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserExtensionTest {
    @Test
    fun `bundled version is derived from the manifest and the worker reports its loaded version`() {
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(bundled("manifest.json")) as kotlinx.serialization.json.JsonObject
        assertEquals(manifest["version"].toString().trim('"'), bundledBrowserExtensionVersion())
        assertEquals(bundledBrowserExtensionVersion(), browserExtensionStatus.value.latestVersion)
        assertTrue(bundled("background.js").contains("chrome.runtime.getManifest().version"))
    }

    @Test
    fun `renamed extension keeps its name and configured trusted identity`() {
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(bundled("manifest.json")) as kotlinx.serialization.json.JsonObject
        assertEquals("\"AutoPilot Page Helper\"", manifest["name"].toString())
        assertEquals("ebilomdffnbhihdfgkeienfcbceomkap", BROWSER_EXTENSION_ID)
    }

    private fun bundled(name: String) = checkNotNull(javaClass.getResourceAsStream("/chrome-extension/$name")) { "Missing $name" }
        .bufferedReader().readText()

    @Test
    fun `given the manifest key when deriving the id then it matches the origin the app trusts`() {
        val key = Regex("\"key\": \"([^\"]+)\"").find(bundled("manifest.json"))!!.groupValues[1]
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key))
        val id = digest.take(16).joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "${'a' + (value shr 4)}${'a' + (value and 0xF)}"
        }
        assertEquals(BROWSER_EXTENSION_ID, id)
    }

    @Test
    fun `given the port when read by each side then they agree`() {
        assertTrue("127.0.0.1:$BROWSER_LINK_PORT" in bundled("manifest.json"))
        assertTrue("127.0.0.1:$BROWSER_LINK_PORT" in bundled("background.js"))
    }

    @Test
    fun `background imports each helper only once`() {
        val background = bundled("background.js")
        listOf("course-automation-background.js", "dgs-elearning-background.js", "dgs-browser-clicks.js", "oceantg-background.js").forEach { helper ->
            assertEquals(1, Regex(Regex.escape(helper)).findAll(background).count(), "$helper must be imported once")
        }
    }

    @Test
    fun `OLP helper retries an unchanged reading so delayed actions are received`() {
        val ocean = bundled("oceantg.js")
        assertTrue("now-lastSentAt>=1500" in ocean)
    }

    @Test
    fun `OLP helper targets the clickable chapter row rather than its heading`() {
        val ocean = bundled("oceantg.js")
        assertTrue("[data-test=\"ChapletRow\"]" in ocean)
    }

    @Test
    fun `OLP helper recognizes clickable hotspot practice answers`() {
        val ocean = bundled("oceantg.js")
        assertTrue("-poText" in ocean)
    }

    @Test
    fun `given the app bundle when writing the extension then every file arrives`() {
        val folder = Files.createTempDirectory("extension").toFile()
        try {
            writeBrowserExtension(folder)
            assertEquals(BROWSER_EXTENSION_FILES.toSet(), folder.list()!!.toSet())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `given the extension's permissions when read then it can only reach this app`() {
        assertEquals("[\"http://127.0.0.1:47613/*\"]", kotlinx.serialization.json.Json.parseToJsonElement(bundled("manifest.json")).let { (it as kotlinx.serialization.json.JsonObject)["host_permissions"].toString() })
        val urls = Regex("https?://[^\"`\\s]+").findAll(bundled("background.js") + bundled("content.js")).map { it.value }.toSet()
        assertEquals(setOf("http://127.0.0.1:47613"), urls)
    }

    @Test
    fun `given an outdated extension folder when syncing then only changed files are rewritten`() {
        val folder = Files.createTempDirectory("extension").toFile()
        try {
            assertEquals(BROWSER_EXTENSION_FILES.size, syncBrowserExtension(folder))
            assertEquals(0, syncBrowserExtension(folder))
            File(folder, "content.js").writeText("old")
            File(folder, "manifest.json").delete()
            assertEquals(2, syncBrowserExtension(folder))
            assertEquals(bundled("content.js"), File(folder, "content.js").readText())
        } finally {
            folder.deleteRecursively()
        }
    }

}
