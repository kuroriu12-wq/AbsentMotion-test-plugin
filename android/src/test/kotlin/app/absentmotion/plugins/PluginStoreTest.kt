package app.absentmotion.plugins

import app.absentmotion.plugins.PluginTestZips.manifest
import app.absentmotion.plugins.PluginTestZips.validZip
import app.absentmotion.plugins.PluginTestZips.zipOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val root get() = File(temp.root, "plugins")

    private val zipFolder by lazy { temp.newFolder("zips") }

    private fun zips() = zipFolder

    @Test fun installThenListPersistsAcrossInstances() {
        val store = PluginStore(root)
        val done = store.install(validZip(zips())) as InstallResult.Done
        assertEquals("kuep.test", done.plugin.id)
        assertTrue(done.plugin.enabled)
        assertEquals(listOf("kuep.test"), store.enabled().map { it.id })
        store.setEnabled("kuep.test", false)
        val again = PluginStore(root)
        val listed = again.list().single()
        assertEquals("kuep.test", listed.id)
        assertFalse(listed.enabled)
        assertNotNull(listed.manifest)
        assertEquals(emptyList<String>(), again.enabled().map { it.id })
    }

    @Test fun reinstallSameIdNeedsConfirm() {
        val store = PluginStore(root)
        store.install(validZip(zips(), version = "1.0", recipeText = "{\"v\":1}"))
        store.setEnabled("kuep.test", false)
        val next = validZip(zips(), version = "2.0", recipeText = "{\"v\":2}")
        val asked = store.install(next) as InstallResult.NeedsConfirm
        assertEquals("1.0", asked.existing.version)
        assertEquals("2.0", asked.incoming.version)
        assertEquals("{\"v\":1}", File(root, "kuep.test/recipes/a.json").readText())
        val done = store.install(next, replace = true) as InstallResult.Done
        assertEquals("2.0", done.plugin.manifest?.version)
        assertEquals("{\"v\":2}", File(root, "kuep.test/recipes/a.json").readText())
        assertFalse(store.list().single().enabled)
        assertFalse(File(root, ".old").exists() && File(root, ".old").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun failedReplaceKeepsOldVersion() {
        val store = PluginStore(root)
        store.install(validZip(zips(), version = "1.0", recipeText = "{\"v\":1}"))
        val bad = File(zips(), "bad.zip").apply { writeText("not a zip") }
        assertTrue(store.install(bad, replace = true) is InstallResult.Rejected)
        assertEquals("{\"v\":1}", File(root, "kuep.test/recipes/a.json").readText())
        assertEquals("1.0", store.list().single().manifest?.version)
        assertEquals(emptyList<File>(), File(root, ".staging").listFiles().orEmpty().toList())
    }

    @Test fun brokenInstalledPluginIsListedWithProblem() {
        val store = PluginStore(root)
        store.install(validZip(zips()))
        File(root, "kuep.test/manifest.json").writeText("garbage {")
        val listed = store.list().single()
        assertEquals("kuep.test", listed.id)
        assertNotNull(listed.problem)
        assertEquals(null, listed.manifest)
        assertEquals(emptyList<String>(), store.enabled().map { it.id })
    }

    @Test fun aFolderWithNoManifestIsListedWithProblem() {
        File(root, "kuep.empty").mkdirs()
        val listed = PluginStore(root).list().single()
        assertNotNull(listed.problem)
    }

    @Test fun scriptsAreRejectedUntilSupported() {
        val store = PluginStore(root)
        val zip = zipOf(zips(), "manifest.json" to manifest(scripts = listOf("scripts/main.lua")), "scripts/main.lua" to "-- hi")
        val result = store.install(zip) as InstallResult.Rejected
        assertTrue(result.problems.any { "scripts" in it })
        assertEquals(emptyList<String>(), store.list().map { it.id })
    }

    @Test fun absentPrefixIsRejectedAtInstall() {
        val zip = zipOf(zips(), "manifest.json" to manifest(id = "absent.glow"))
        assertTrue(PluginStore(root).install(zip) is InstallResult.Rejected)
    }

    @Test fun removeDeletesFolderAndState() {
        val store = PluginStore(root)
        store.install(validZip(zips()))
        store.setEnabled("kuep.test", false)
        store.remove("kuep.test")
        assertFalse(File(root, "kuep.test").exists())
        assertEquals(emptyList<String>(), PluginStore(root).list().map { it.id })
        assertFalse(File(root, "state.json").readText().contains("kuep.test"))
    }

    @Test fun removeIgnoresUnsafeIds() {
        val store = PluginStore(root)
        store.install(validZip(zips()))
        store.remove("../plugins")
        store.remove("")
        assertEquals(listOf("kuep.test"), store.list().map { it.id })
    }

    @Test fun strayStagingFoldersAreIgnoredAndCleaned() {
        File(root, ".staging/x").mkdirs()
        File(root, ".staging/x/file.txt").writeText("half written")
        val store = PluginStore(root)
        assertEquals(emptyList<String>(), store.list().map { it.id })
        assertEquals(emptyList<File>(), File(root, ".staging").listFiles().orEmpty().toList())
    }

    @Test fun anInterruptedReplaceIsRolledBackOnNextStart() {
        val store = PluginStore(root)
        store.install(validZip(zips(), version = "1.0"))
        val old = File(root, ".old/kuep.test")
        old.parentFile.mkdirs()
        assertTrue(File(root, "kuep.test").renameTo(old))
        val recovered = PluginStore(root)
        assertEquals("1.0", recovered.list().single().manifest?.version)
        assertFalse(old.exists())
    }
}
