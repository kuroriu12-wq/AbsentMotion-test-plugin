package app.absentmotion.plugins

import app.absentmotion.plugins.PluginTestZips.manifest
import app.absentmotion.plugins.PluginTestZips.validZip
import app.absentmotion.plugins.PluginTestZips.zipOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginsModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val zipFolder by lazy { temp.newFolder("zips") }

    private fun model() = PluginsModel(PluginStore(File(temp.root, "plugins")))

    private fun effectPack(id: String = "kuep.pack"): File =
        zipOf(zipFolder, "manifest.json" to manifest(id, effects = listOf("pulse")), "effects/pulse.json" to "{}")

    @Test fun installShowsItAndRejectedShowsReason() {
        val model = model()
        model.install(validZip(zipFolder))
        assertEquals(listOf("kuep.test"), model.items.map { it.id })
        assertNull(model.message)
        val notAZip = File(zipFolder, "bad.zip").apply { writeText("nope") }
        model.install(notAZip)
        assertNotNull(model.message)
        assertEquals(listOf("kuep.test"), model.items.map { it.id })
    }

    @Test fun aMissingFileIsReportedNotThrown() {
        val model = model()
        model.install(File(zipFolder, "gone.zip"))
        assertNotNull(model.message)
        assertEquals(emptyList<String>(), model.items.map { it.id })
    }

    @Test fun reinstallSetsPendingReplaceThenConfirmReplaces() {
        val model = model()
        model.install(validZip(zipFolder, version = "1.0"))
        val newer = validZip(zipFolder, version = "2.0")
        model.install(newer)
        val pending = model.pendingReplace!!
        assertEquals("1.0", pending.existing.version)
        assertEquals("2.0", pending.incoming.version)
        assertEquals("1.0", model.items.single().manifest!!.version)
        model.confirmReplace(newer)
        assertNull(model.pendingReplace)
        assertEquals("2.0", model.items.single().manifest!!.version)
    }

    @Test fun cancelReplaceClearsIt() {
        val model = model()
        model.install(validZip(zipFolder, version = "1.0"))
        model.install(validZip(zipFolder, version = "2.0"))
        assertNotNull(model.pendingReplace)
        model.cancelReplace()
        assertNull(model.pendingReplace)
        assertEquals("1.0", model.items.single().manifest!!.version)
    }

    @Test fun restartNeededOnlyWhenAPluginWithEffectsChanges() {
        val model = model()
        model.install(validZip(zipFolder, id = "kuep.recipes"))
        assertFalse(model.restartNeeded)
        model.toggle("kuep.recipes")
        assertFalse(model.restartNeeded)
        model.remove("kuep.recipes")
        assertFalse(model.restartNeeded)
        model.install(effectPack())
        assertTrue(model.restartNeeded)
    }

    @Test fun togglingAnEffectPackNeedsARestart() {
        val model = model()
        model.install(effectPack())
        val fresh = PluginsModel(PluginStore(File(temp.root, "plugins")))
        assertFalse(fresh.restartNeeded)
        fresh.toggle("kuep.pack")
        assertTrue(fresh.restartNeeded)
    }

    @Test fun removingAnEffectPackNeedsARestart() {
        val model = model()
        model.install(effectPack())
        val fresh = PluginsModel(PluginStore(File(temp.root, "plugins")))
        fresh.remove("kuep.pack")
        assertTrue(fresh.restartNeeded)
    }

    @Test fun toggleAndRemoveUpdateItems() {
        val model = model()
        model.install(validZip(zipFolder))
        model.toggle("kuep.test")
        assertFalse(model.items.single().enabled)
        model.toggle("kuep.test")
        assertTrue(model.items.single().enabled)
        model.remove("kuep.test")
        assertEquals(emptyList<String>(), model.items.map { it.id })
    }

    @Test fun dismissMessageClearsIt() {
        val model = model()
        model.install(File(zipFolder, "gone.zip"))
        assertNotNull(model.message)
        model.dismissMessage()
        assertNull(model.message)
    }

    @Test fun aNewInstallClearsTheOldMessage() {
        val model = model()
        model.install(File(zipFolder, "gone.zip"))
        assertNotNull(model.message)
        model.install(validZip(zipFolder))
        assertNull(model.message)
    }
}
