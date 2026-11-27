package app.absentmotion.plugins.script

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.keyframes
import app.absentmotion.plugins.InstallResult
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.PluginTestZips.manifest
import app.absentmotion.plugins.PluginTestZips.zipOf
import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptRunnerTest {
    @get:Rule val temp = TemporaryFolder()

    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val scene = sceneOf(shapeLayer().node)

    private fun runner(budget: ScriptBudget = ScriptBudget(millis = 3_000)) = ScriptRunner(catalog) { QuickJsSandbox(budget) }

    private fun scriptOf(body: String, inputs: String = "[]"): PluginScript {
        val source = "defineScript({ id: \"t\", name: \"T\", inputs: $inputs, run: function (ctx) { $body } });"
        val store = PluginStore(File(temp.root, "plugins"))
        val zip = zipOf(temp.newFolder("zips"), "manifest.json" to manifest(id = "kuep.test", scripts = listOf("scripts/a.js")), "scripts/a.js" to source)
        assertTrue(store.install(zip) is InstallResult.Done)
        return loadScripts(store, QuickJsSandbox(ScriptLoadBudget)).scripts.single()
    }

    private fun keysOf(result: ScriptResult) =
        (result as ScriptResult.Done).scene.layer(1)!!.node.child("transform")?.child("opacity")?.keyframes.orEmpty()

    @Test fun successReturnsNewSceneAndLog() {
        val result = runner().run(scene, listOf(1), scriptOf("ctx.log('hi'); ctx.selection()[0].setKeys('opacity', [0, 1], [0, 1]);"), emptyMap(), 0)
        assertEquals(2, keysOf(result).size)
        assertEquals(listOf("hi"), (result as ScriptResult.Done).log)
    }

    @Test fun failureLeavesSceneUntouched() {
        val before = scene.node
        val result = runner().run(scene, listOf(1), scriptOf("ctx.log('a');\nthrow new Error('boom');"), emptyMap(), 0)
        result as ScriptResult.Failed
        assertTrue(result.reason, result.reason.contains("boom"))
        assertEquals(2, result.line)
        assertEquals(listOf("a"), result.log)
        assertEquals(before, scene.node)
    }

    @Test fun badInputFailsBeforeRunning() {
        val script = scriptOf(
            "ctx.log('ran');",
            "[{ id: \"n\", label: \"Amount\", type: \"number\", default: 1, min: 0, max: 5 }]",
        )
        val result = runner().run(scene, listOf(1), script, mapOf("n" to "9"), 0) as ScriptResult.Failed
        assertTrue(result.reason, result.reason.startsWith("Amount:"))
        assertTrue(result.log.isEmpty())
    }

    @Test fun inputsReachTheScriptTyped() {
        val script = scriptOf(
            "ctx.log(typeof ctx.input.n + ctx.input.n + typeof ctx.input.s + ctx.input.s);",
            "[{ id: \"n\", label: \"N\", type: \"number\", default: 1 }, { id: \"s\", label: \"S\", type: \"switch\", default: false }]",
        )
        val result = runner().run(scene, listOf(1), script, mapOf("n" to "2.5", "s" to "true"), 0) as ScriptResult.Done
        assertEquals(listOf("number2.5booleantrue"), result.log)
    }

    @Test fun overTimeLimitFails() {
        val result = runner(ScriptBudget(millis = 400)).run(scene, listOf(1), scriptOf("while (true) {}"), emptyMap(), 0) as ScriptResult.Failed
        assertTrue(result.reason, result.reason.contains("longer than"))
    }

    @Test fun overMemoryLimitFails() {
        val budget = ScriptBudget(millis = 3_000, memoryBytes = 16L * 1024 * 1024)
        val result = runner(budget).run(scene, listOf(1), scriptOf("var s = 'x'.repeat(1e9); ctx.log(s.length);"), emptyMap(), 0) as ScriptResult.Failed
        assertTrue(result.reason, result.reason.contains("memory"))
    }

    @Test fun cancelFails() {
        val started = System.nanoTime()
        val cancelled = { System.nanoTime() - started > 150_000_000L }
        val result = runner().run(scene, listOf(1), scriptOf("while (true) {}"), emptyMap(), 0, cancelled = cancelled) as ScriptResult.Failed
        assertTrue(result.reason, result.reason.contains("cancelled"))
    }

    @Test fun scriptThatEditsThenThrowsCommitsNothing() {
        val result = runner().run(scene, listOf(1), scriptOf("ctx.selection()[0].setKeys('opacity', [0, 1], [0, 1]); throw new Error('x');"), emptyMap(), 0)
        assertTrue(result is ScriptResult.Failed)
        assertNull(scene.layer(1)!!.node.child("transform")?.child("opacity"))
    }

    @Test fun noSelectionFailsWithAMessage() {
        val result = runner().run(scene, emptyList(), scriptOf("ctx.log('ran');"), emptyMap(), 0) as ScriptResult.Failed
        assertEquals("select at least one layer first", result.reason)
    }
}
