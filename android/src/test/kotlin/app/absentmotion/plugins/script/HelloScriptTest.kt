package app.absentmotion.plugins.script

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.plugins.InstallResult
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.PluginTestZips.zipOf
import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HelloScriptTest {
    @get:Rule val temp = TemporaryFolder()

    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val sample = File("src/test/resources/plugins/hello_script")

    private fun nudge(): PluginScript {
        val store = PluginStore(File(temp.root, "plugins"))
        val zip = zipOf(temp.newFolder("zips"), "manifest.json" to File(sample, "manifest.json").readText(), "scripts/nudge.js" to File(sample, "scripts/nudge.js").readText())
        assertTrue(store.install(zip) is InstallResult.Done)
        val listing = loadScripts(store, QuickJsSandbox(ScriptLoadBudget))
        assertEquals(emptyMap<String, String>(), listing.problems)
        return listing.scripts.single()
    }

    private fun layerAt(id: Long, x: Int, y: Int) =
        shapeLayer(id = id, children = listOf(XmlNode("transform", emptyMap(), listOf(XmlNode("location", linkedMapOf("value" to "$x,$y,0"))))))

    private val runner = ScriptRunner(catalog) { QuickJsSandbox(ScriptBudget(millis = 3_000)) }

    @Test fun loadsWithItsThreeInputs() {
        val script = nudge()
        assertEquals("nudge", script.id)
        assertEquals(listOf("dx", "dy", "seconds"), script.inputs.map { it.id })
    }

    @Test fun movesEverySelectedLayerWithTwoKeys() {
        val scene = sceneOf(layerAt(1, 10, 20).node, layerAt(2, 300, 40).node)
        val result = runner.run(scene, listOf(1, 2), nudge(), mapOf("dx" to "100", "dy" to "-5", "seconds" to "1"), 0) as ScriptResult.Done
        val first = result.scene.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes
        val second = result.scene.layer(2)!!.node.child("transform")!!.child("location")!!.keyframes
        assertEquals(listOf(0f, 0.5f), first.map { it.t })
        assertEquals(listOf("10.000000,20.000000,0.000000", "110.000000,15.000000,0.000000"), first.map { it.v })
        assertEquals(listOf("300.000000,40.000000,0.000000", "400.000000,35.000000,0.000000"), second.map { it.v })
        assertEquals(2, result.log.size)
    }

    @Test fun durationLongerThanTheLayerIsClamped() {
        val scene = sceneOf(layerAt(1, 0, 0).node)
        val result = runner.run(scene, listOf(1), nudge(), mapOf("seconds" to "60"), 0) as ScriptResult.Done
        assertEquals(listOf(0f, 1f), result.scene.layer(1)!!.node.child("transform")!!.child("location")!!.keyframes.map { it.t })
    }

    @Test fun nothingSelectedSaysSo() {
        val result = runner.run(sceneOf(layerAt(1, 0, 0).node), emptyList(), nudge(), emptyMap(), 0) as ScriptResult.Failed
        assertEquals("select at least one layer first", result.reason)
    }
}
