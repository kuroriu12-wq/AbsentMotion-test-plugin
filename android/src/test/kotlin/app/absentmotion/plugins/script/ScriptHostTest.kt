package app.absentmotion.plugins.script

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.keyframes
import app.absentmotion.plugins.RecipeRunner
import app.absentmotion.plugins.Recipe
import app.absentmotion.plugins.RecipeStep
import app.absentmotion.plugins.RunResult
import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ScriptHostTest {
    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val engine = QuickJsSandbox(ScriptBudget(millis = 5_000))

    private fun transformed(location: String? = null, rotation: String? = null, lock: String? = null, id: Long = 1): Layer {
        val parts = listOfNotNull(
            location?.let { XmlNode("location", linkedMapOf("value" to it)) },
            rotation?.let { XmlNode("rotation", linkedMapOf("value" to it)) },
        )
        return shapeLayer(id = id, attrs = lock?.let { mapOf("lock" to it) } ?: emptyMap(), children = listOf(XmlNode("transform", emptyMap(), parts)))
    }

    private fun sceneWith(vararg layers: Layer): Scene = sceneOf(*layers.map { it.node }.toTypedArray())

    private fun hostFor(scene: Scene, selection: List<Long> = listOf(1L), limits: HostLimits = HostLimits(), inputs: Map<String, Any> = emptyMap()) =
        ScriptHost(PendingScene(scene), selection, 0, inputs, catalog, limits)

    private fun run(host: ScriptHost, body: String): SandboxResult =
        engine.run(runnableSource("defineScript({ run: function (ctx) { $body } });"), "test.js", host.context())

    private fun pendingOf(host: ScriptHost): PendingScene =
        ScriptHost::class.java.getDeclaredField("pending").also { it.isAccessible = true }.get(host) as PendingScene

    private fun layerAfter(host: ScriptHost, id: Long = 1) = pendingOf(host).scene.layer(id)!!

    private fun succeeded(result: SandboxResult) {
        if (result is SandboxResult.Failed) fail("the script failed: ${result.reason}")
    }

    private fun failureOf(result: SandboxResult): String = (result as SandboxResult.Failed).reason

    @Suppress("UNCHECKED_CAST")
    private fun ops(host: ScriptHost): Map<String, HostFunction> = host.context()["__ops"] as Map<String, HostFunction>

    @Test fun readsSceneAndSelection() {
        val host = hostFor(sceneWith(shapeLayer()), inputs = mapOf("strength" to 3))
        succeeded(run(host, "var l = ctx.selection()[0]; ctx.log(JSON.stringify([ctx.scene.width, ctx.scene.height, ctx.scene.fps, l.id, l.kind, l.start, l.finish, l.locked, ctx.input.strength]));"))
        assertEquals(listOf("[400,400,30,1,\"shape\",0,2,false,3]"), host.log)
    }

    @Test fun shapeAndScaleAreReadable() {
        val layer = shapeLayer(attrs = mapOf("s" to ".circle"), children = listOf(XmlNode("transform", emptyMap(), listOf(XmlNode("scale", linkedMapOf("value" to "2,3"))))))
        val host = hostFor(sceneWith(layer))
        succeeded(run(host, "var l = ctx.selection()[0]; var s = l.scale(0); ctx.log(l.shape + ',' + s.x + ',' + s.y);"))
        assertEquals(listOf(".circle,2,3"), host.log)
    }

    @Test fun positionAndRotationRoundTrip() {
        val host = hostFor(sceneWith(transformed(location = "100,200,0", rotation = "-30")))
        succeeded(run(host, "var l = ctx.selection()[0]; var p = l.position(0); ctx.log(p.x + ',' + p.y + ',' + l.rotation(0)); l.setKeys('rotation', [0, 1], [10, 45]); l.setKeys('position', [0], [{ x: 5, y: 6 }]);"))
        assertEquals(listOf("100,200,30"), host.log)
        val layer = layerAfter(host)
        assertEquals(listOf("-10.000000", "-45.000000"), layer.node.child("transform")!!.child("rotation")!!.keyframes.map { it.v })
        assertEquals(listOf("5.000000,6.000000,0.000000"), layer.node.child("transform")!!.child("location")!!.keyframes.map { it.v })
    }

    @Test fun setKeysWritesNormalisedTimes() {
        val host = hostFor(sceneWith(transformed(location = "0,0,0")))
        succeeded(run(host, "ctx.selection()[0].setKeys('opacity', [0, 0.5, 2], [0, 0.5, 1]);"))
        val keys = layerAfter(host).node.child("transform")!!.child("opacity")!!.keyframes
        assertEquals(listOf(0f, 0.25f, 1f), keys.map { it.t })
        assertEquals(listOf("0.000000", "0.500000", "1.000000"), keys.map { it.v })
    }

    @Test fun effectOperationsMatchTheRecipeRunner() {
        val scene = sceneWith(shapeLayer())
        val host = hostFor(scene)
        succeeded(run(host, "var l = ctx.selection()[0]; l.addEffect('absent.glitch', { intensity: 7 }); l.setEffectParam('absent.glitch', 'intensity', 3);"))
        val viaRecipe = RecipeRunner(catalog).run(
            scene, 1,
            Recipe("r", "R", emptyList(), listOf(RecipeStep.AddEffect("absent.glitch", null, mapOf("intensity" to "7")), RecipeStep.SetEffectParam("absent.glitch", "intensity", "3"))),
            emptyMap(), 0,
        )
        val expected = (viaRecipe as RunResult.Done).scene.layer(1)!!.effects.single()
        val actual = layerAfter(host).effects.single()
        assertEquals(expected["id"], actual["id"])
        assertEquals(expected.effectProperty("intensity"), actual.effectProperty("intensity"))
    }

    @Test fun lockedLayerIsRefused() {
        val host = hostFor(sceneWith(transformed(lock = "all")))
        val reason = failureOf(run(host, "ctx.selection()[0].setKeys('opacity', [0], [1]);"))
        assertTrue(reason, reason.contains("locked"))
    }

    @Test fun unknownLayerIdIsRefused() {
        val host = hostFor(sceneWith(shapeLayer()), selection = listOf(99L))
        val reason = failureOf(run(host, "ctx.selection();"))
        assertTrue(reason, reason.contains("no longer exists"))
    }

    @Test fun layersOutsideTheSelectionAreRefused() {
        val host = hostFor(sceneWith(shapeLayer(), shapeLayer(id = 2)))
        val setKeys = ops(host).getValue("setKeys")
        try {
            setKeys(listOf(2L, "opacity", listOf(0.0), listOf(1.0)))
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("not part of the selection"))
        }
    }

    @Test fun nanAndInfinityAreRefused() {
        val host = hostFor(sceneWith(shapeLayer()))
        val setKeys = ops(host).getValue("setKeys")
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            try {
                setKeys(listOf(1L, "opacity", listOf(0.0), listOf(bad)))
                fail("expected a refusal for $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.contains("finite"))
            }
        }
    }

    @Test fun timesMustBeAscendingAndInsideTheLayer() {
        val host = hostFor(sceneWith(shapeLayer()))
        val unordered = failureOf(run(host, "ctx.selection()[0].setKeys('opacity', [1, 0.5], [0, 1]);"))
        assertTrue(unordered, unordered.contains("ascending"))
        val outside = failureOf(run(host, "ctx.selection()[0].setKeys('opacity', [3], [1]);"))
        assertTrue(outside, outside.contains("outside the layer"))
    }

    @Test fun keyframeCapStopsAFlood() {
        val host = hostFor(sceneWith(shapeLayer()), limits = HostLimits(maxKeyframes = 5))
        val reason = failureOf(run(host, "ctx.selection()[0].setKeys('opacity', [0, 0.1, 0.2, 0.3, 0.4, 0.5], [0, 0, 0, 0, 0, 0]);"))
        assertTrue(reason, reason.contains("too many keyframes"))
    }

    @Test fun inputsAreTyped() {
        val host = hostFor(sceneWith(shapeLayer()), inputs = mapOf("count" to 4, "on" to true, "name" to "x"))
        succeeded(run(host, "ctx.log(typeof ctx.input.count + typeof ctx.input.on + typeof ctx.input.name);"))
        assertEquals(listOf("numberbooleanstring"), host.log)
    }

    @Test fun hostObjectsCannotBeReplaced() {
        val host = hostFor(sceneWith(shapeLayer()))
        succeeded(run(host, "'use strict'; var failed = 0; try { ctx.log = null; } catch (e) { failed++; } try { ctx.scene.width = 1; } catch (e) { failed++; } try { ctx.selection()[0].id = 5; } catch (e) { failed++; } try { globalThis.__ops = null; } catch (e) { failed++; } ctx.log(String(failed) + ' ' + typeof globalThis.__ops + ' ' + typeof globalThis.__init);"))
        assertEquals(listOf("3 object undefined"), host.log)
    }

    @Test fun boundsComeFromTheGivenBoxesOrAreNull() {
        val host = ScriptHost(PendingScene(sceneWith(shapeLayer(id = 1), shapeLayer(id = 2))), listOf(1L, 2L), 0, emptyMap(), catalog, bounds = mapOf(1L to floatArrayOf(10f, 20f, 110f, 70f)))
        succeeded(run(host, "var l = ctx.selection(); var b = l[0].bounds(); ctx.log([b.left, b.top, b.right, b.bottom].join(',')); ctx.log(String(l[1].bounds()));"))
        assertEquals(listOf("10,20,110,70", "null"), host.log)
    }

    @Test fun nonFiniteBoundsAreIgnored() {
        val host = ScriptHost(PendingScene(sceneWith(shapeLayer(id = 1))), listOf(1L), 0, emptyMap(), catalog, bounds = mapOf(1L to floatArrayOf(Float.NaN, 0f, 1f, 1f)))
        succeeded(run(host, "ctx.log(JSON.stringify(ctx.selection()[0].bounds()));"))
        assertEquals(listOf("null"), host.log)
    }
}
