package app.absentmotion.plugins.script

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.keyframes
import app.absentmotion.model.withKeyframes
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

class Physics2dScriptTest {
    @get:Rule val temp = TemporaryFolder()

    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val sample = File("src/test/resources/plugins/physics2d")
    private val runner = ScriptRunner(catalog) { QuickJsSandbox(ScriptBudget(millis = 30_000)) }

    private val script: PluginScript by lazy {
        val store = PluginStore(File(temp.root, "plugins"))
        val zip = zipOf(temp.newFolder("zips"), "manifest.json" to File(sample, "manifest.json").readText(), "scripts/physics2d.js" to File(sample, "scripts/physics2d.js").readText())
        assertTrue(store.install(zip) is InstallResult.Done)
        val listing = loadScripts(store, QuickJsSandbox(ScriptLoadBudget))
        assertEquals(emptyMap<String, String>(), listing.problems)
        listing.scripts.single()
    }

    private fun transform(vararg parts: XmlNode) = XmlNode("transform", emptyMap(), parts.toList())

    private fun at(x: Float, y: Float) = XmlNode("location", linkedMapOf("value" to "$x,$y,0"))

    private fun body(id: Long, x: Float, y: Float, shape: String = ".circle", width: Int = 40, height: Int = 40, seconds: Int = 6, tilt: Float = 0f, lock: Boolean = false): Layer =
        shapeLayer(
            id = id, end = seconds * 1000,
            attrs = mapOf("s" to shape) + if (lock) mapOf("lock" to "all") else emptyMap(),
            children = listOf(
                XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2", "value" to "$width,$height")),
                transform(at(x, y), XmlNode("rotation", linkedMapOf("value" to "${-tilt}"))),
            ),
        )

    private fun moving(id: Long, from: Float, to: Float, y: Float, seconds: Int): Layer {
        val location = at(from, y).withKeyframes(listOf(Keyframe(0f, "$from,$y,0"), Keyframe(1f, "$to,$y,0")))
        return shapeLayer(
            id = id, end = seconds * 1000, attrs = mapOf("s" to ".circle"),
            children = listOf(XmlNode("property", linkedMapOf("name" to "size", "type" to "vec2", "value" to "40,40")), transform(location)),
        )
    }

    private fun scene(vararg layers: Layer, fps: Int = 30): Scene = sceneOf(*layers.map { it.node }.toTypedArray(), fps = fps)

    private val off = mapOf("left" to "false", "right" to "false")

    private fun run(scene: Scene, ids: List<Long>, inputs: Map<String, String> = emptyMap()): ScriptResult = runner.run(scene, ids, script, inputs, 0)

    private fun done(result: ScriptResult): Scene = (result as? ScriptResult.Done)?.scene ?: throw AssertionError("failed: " + (result as ScriptResult.Failed).reason)

    private fun path(scene: Scene, id: Long): List<Pair<Float, Float>> =
        scene.layer(id)!!.node.child("transform")!!.child("location")!!.keyframes.map { key -> key.v.split(",").let { it[0].toFloat() to it[1].toFloat() } }

    private fun turns(scene: Scene, id: Long): List<Float> =
        scene.layer(id)!!.node.child("transform")!!.child("rotation")!!.keyframes.map { -it.v.toFloat() }

    private fun failure(result: ScriptResult) = (result as ScriptResult.Failed).reason

    @Test fun ballRestsOnTheFloor() {
        val result = done(run(scene(body(1, 200f, 100f)), listOf(1)))
        val p = path(result, 1)
        assertEquals(380f, p.last().second, 0.5f)
        assertEquals(p.last().second, p[p.size - 31].second, 0.2f)
    }

    @Test fun bounceHeightFollowsRestitution() {
        val result = done(run(scene(body(1, 200f, 100f, seconds = 3), fps = 240), listOf(1), off + mapOf("restitution" to "0.5", "friction" to "0")))
        val y = path(result, 1).map { it.second }
        val hit = y.indexOfFirst { it >= 379.9f }
        var apex = Float.MAX_VALUE
        for (i in hit + 1 until y.size) {
            if (y[i] < apex) apex = y[i] else if (y[i] > apex + 0.5f && i > hit + 5) break
        }
        val rise = 380f - apex
        assertEquals(0.25f * 280f, rise, 0.05f * 0.25f * 280f)
    }

    @Test fun equalBallsHeadOnConserveMomentum() {
        val result = done(
            run(
                scene(moving(1, 100f, 500f, 200f, 4), moving(2, 300f, -100f, 200f, 4), fps = 60), listOf(1, 2),
                off + mapOf("gravity" to "0", "restitution" to "1", "friction" to "0", "floor" to "false", "inherit" to "true"),
            ),
        )
        fun speed(id: Long): Float = path(result, id).let { (it.last().first - it[it.size - 2].first) * 60f }
        assertEquals(-100f, speed(1), 1f)
        assertEquals(100f, speed(2), 1f)
        assertEquals(0f, speed(1) + speed(2), 0.1f)
        assertEquals(20_000f, speed(1) * speed(1) + speed(2) * speed(2), 200f)
    }

    @Test fun pinnedLayerNeverMoves() {
        val result = done(run(scene(body(1, 200f, 300f, ".rect", 200, 20, 5), body(2, 200f, 50f, seconds = 5)), listOf(1, 2), mapOf("pin" to "true")))
        assertEquals(1, path(result, 1).toSet().size)
        assertEquals(270f, path(result, 2).last().second, 0.6f)
    }

    @Test fun boxLandsFlatAndStops() {
        val result = done(run(scene(body(1, 200f, 100f, ".rect", 80, 40, 8, tilt = 30f)), listOf(1)))
        val turn = turns(result, 1)
        val folded = ((turn.last() % 90f) + 90f) % 90f
        assertTrue("rotation $folded", minOf(folded, 90f - folded) < 1f)
        val p = path(result, 1)
        assertEquals(p.last().second, p[p.size - 31].second, 0.2f)
        assertEquals(380f, p.last().second, 1f)
    }

    @Test fun wallsKeepBodiesInsideTheFrame() {
        val layers = (0 until 8).map { body(it + 1L, 60f + it * 40f, 50f + (it % 3) * 30f, seconds = 8) }
        val result = done(run(scene(*layers.toTypedArray()), layers.map { it.id }, mapOf("pushX" to "1.2", "pushY" to "-1", "ceiling" to "true")))
        for (layer in layers) for ((x, y) in path(result, layer.id)) {
            assertTrue("x $x", x > -20f && x < 420f)
            assertTrue("y $y", y > -20f && y < 420f)
        }
    }

    @Test fun deterministic() {
        fun make() = scene(body(1, 100f, 50f, seconds = 6), body(2, 110f, 0f, ".rect", 50, 30, 6, tilt = 10f))
        val first = done(run(make(), listOf(1, 2)))
        val second = done(run(make(), listOf(1, 2)))
        assertEquals(first.node, second.node)
    }

    @Test fun fitsTheLimits() {
        val layers = (0 until 10).map { body(it + 1L, 60f + (it % 5) * 70f, 40f + (it / 5) * 60f, if (it % 2 == 1) ".rect" else ".circle", seconds = 10) }
        val started = System.nanoTime()
        done(run(scene(*layers.toTypedArray()), layers.map { it.id }))
        val millis = (System.nanoTime() - started) / 1_000_000
        println("physics2d: 10 bodies, 10 s, 30 fps took $millis ms")
        assertTrue("took $millis ms", millis < 5_000)
    }

    @Test fun tooManyBodiesFailsPlainly() {
        val layers = (0 until 41).map { body(it + 1L, 10f + it * 8f, 50f, seconds = 1) }
        val reason = failure(run(scene(*layers.toTypedArray()), layers.map { it.id }))
        assertTrue(reason, reason.contains("at most 40"))
    }

    @Test fun tooManyKeyframesFailsPlainly() {
        val layers = (0 until 40).map { body(it + 1L, 10f + it * 9f, 50f, seconds = 10) }
        val reason = failure(run(scene(*layers.toTypedArray()), layers.map { it.id }))
        assertTrue(reason, reason.contains("keyframes"))
    }

    @Test fun lockedLayerFailsPlainly() {
        val reason = failure(run(scene(body(1, 200f, 100f, lock = true)), listOf(1)))
        assertTrue(reason, reason.contains("locked"))
    }

    @Test fun nothingSelectedFailsPlainly() {
        assertEquals("select at least one layer first", failure(run(scene(body(1, 200f, 100f)), emptyList())))
    }

    @Test fun layerWithoutSizeFallsBackToACircle() {
        val bare = shapeLayer(id = 1, kind = "text", end = 4000, children = listOf(transform(at(200f, 100f))))
        val result = done(run(scene(bare), listOf(1)))
        assertEquals(400f - 0.05f * 400f, path(result, 1).last().second, 1f)
    }

    private fun unsized(id: Long, x: Float, y: Float, tilt: Float = 0f): Layer =
        shapeLayer(id = id, end = 6000, attrs = mapOf("s" to ".star"), children = listOf(transform(at(x, y), XmlNode("rotation", linkedMapOf("value" to "${-tilt}")))))

    @Test fun layerWithoutSizeCollidesAsItsMeasuredBox() {
        val layers = scene(unsized(1, 200f, 300f), body(2, 200f, 50f, seconds = 6))
        val boxes = mapOf(1L to floatArrayOf(100f, 290f, 300f, 310f))
        val result = done(runner.run(layers, listOf(1, 2), script, mapOf("pin" to "true"), 0, boxes))
        assertEquals(270f, path(result, 2).last().second, 0.6f)
        assertEquals(1, path(result, 1).toSet().size)
    }

    @Test fun measuredBoxOffCentreKeepsTheLayerWhereItWas() {
        val layers = scene(unsized(1, 150f, 300f), body(2, 200f, 50f, seconds = 6))
        val boxes = mapOf(1L to floatArrayOf(100f, 290f, 300f, 310f))
        val result = done(runner.run(layers, listOf(1, 2), script, mapOf("pin" to "true"), 0, boxes))
        assertEquals(150f, path(result, 1).last().first, 0.01f)
        assertEquals(300f, path(result, 1).last().second, 0.01f)
        assertEquals(270f, path(result, 2).last().second, 0.6f)
    }

    @Test fun logNamesTheShapeUsedForEachLayer() {
        val layers = scene(unsized(1, 200f, 300f), body(2, 200f, 50f, seconds = 6))
        val boxes = mapOf(1L to floatArrayOf(100f, 290f, 300f, 310f))
        val log = (runner.run(layers, listOf(1, 2), script, emptyMap(), 0, boxes) as ScriptResult.Done).log
        assertTrue(log.toString(), log.any { "box 200 x 20" in it })
        assertTrue(log.toString(), log.any { "circle of radius 20" in it })
    }

    @Test fun layerWithoutSizeOrBoundsFallsBackToACircle() {
        val log = (run(scene(unsized(1, 200f, 100f)), listOf(1)) as ScriptResult.Done).log
        assertTrue(log.toString(), log.any { "no size known" in it })
    }
}
