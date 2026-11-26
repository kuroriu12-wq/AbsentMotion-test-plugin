package app.absentmotion.plugins.script

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withKeyframes
import app.absentmotion.plugins.LayerOps
import kotlin.math.roundToInt

class PendingScene(var scene: Scene)

data class HostLimits(val maxKeyframes: Int = 20_000, val maxLayers: Int = 200)

private const val MAX_LOG_LINES = 100

private const val MAX_LOG_CHARS = 500

private const val KEY_EPSILON = 1e-4

private val PROPERTIES = mapOf(
    "position" to PropRef.Location,
    "rotation" to PropRef.Rotation,
    "scale" to PropRef.Scale,
    "opacity" to PropRef.Opacity,
)

private val RUN_PRELUDE = listOf(
    "(function () {",
    "var host = globalThis.__ops;",
    "var init = globalThis.__init;",
    "var freezeObject = Object.freeze;",
    "var map = Array.prototype.map;",
    "var spec;",
    "delete globalThis.__ops;",
    "delete globalThis.__init;",
    "globalThis.defineScript = function (value) {",
    "if (value === null || typeof value !== 'object' || typeof value.run !== 'function') throw new Error('defineScript needs an object with a run function');",
    "spec = value;",
    "};",
    "globalThis.__main = function () {",
    "if (spec === undefined) throw new Error('the script never calls defineScript');",
    "function handle(id) {",
    "var info = host.layer(id);",
    "return freezeObject({",
    "id: info.id, kind: info.kind, name: info.name, shape: info.shape, start: info.start, finish: info.finish, locked: info.locked,",
    "size: function () { return host.size(id); },",
    "position: function (t) { return host.position(id, t); },",
    "rotation: function (t) { return host.rotation(id, t); },",
    "scale: function (t) { return host.scale(id, t); },",
    "setKeys: function (property, times, values) { host.setKeys(id, property, times, values); },",
    "addEffect: function (effect, params) { host.addEffect(id, effect, params === undefined ? {} : params); },",
    "setEffectParam: function (effect, param, value) { host.setEffectParam(id, effect, param, value); },",
    "keyframeEffectParam: function (effect, param, times, values) { host.keyframeEffectParam(id, effect, param, times, values); }",
    "});",
    "}",
    "var ctx = freezeObject({",
    "scene: init.scene, input: init.input,",
    "selection: function () { return map.call(init.selection, handle); },",
    "log: function (text) { host.log(String(text)); }",
    "});",
    "globalThis.console = freezeObject({ log: ctx.log });",
    "spec.run(ctx);",
    "};",
    "})();",
).joinToString(" ")

fun runnableSource(source: String): String = RUN_PRELUDE + source + "\n;__main();"

class ScriptHost(
    private val pending: PendingScene,
    private val selection: List<Long>,
    private val sampleMs: Int,
    private val inputs: Map<String, Any>,
    catalog: EffectCatalog,
    private val limits: HostLimits = HostLimits(),
) {
    private val ops = LayerOps(catalog)
    private val lines = ArrayList<String>()
    private var keyframes = 0

    val log: List<String> get() = lines

    fun context(): Map<String, Any?> {
        require(selection.size <= limits.maxLayers) { "too many layers are selected (the limit is ${limits.maxLayers})" }
        val scene = pending.scene
        return mapOf(
            "__init" to mapOf(
                "scene" to mapOf("width" to scene.width, "height" to scene.height, "fps" to scene.fps, "duration" to scene.totalTime / 1000.0),
                "input" to inputs,
                "selection" to selection,
            ),
            "__ops" to mapOf<String, Any?>(
                "log" to guarded("ctx.log") { addLog(it.firstOrNull()) },
                "layer" to guarded("layer") { describe(layerOf(it.getOrNull(0), write = false)) },
                "size" to guarded("size") { sizeOf(layerOf(it.getOrNull(0), write = false)) },
                "position" to guarded("position") { positionAt(layerOf(it.getOrNull(0), write = false), it.getOrNull(1)) },
                "rotation" to guarded("rotation") { rotationAt(layerOf(it.getOrNull(0), write = false), it.getOrNull(1)) },
                "scale" to guarded("scale") { scaleAt(layerOf(it.getOrNull(0), write = false), it.getOrNull(1)) },
                "setKeys" to guarded("setKeys") { setKeys(it) },
                "addEffect" to guarded("addEffect") { addEffect(it) },
                "setEffectParam" to guarded("setEffectParam") { setEffectParam(it) },
                "keyframeEffectParam" to guarded("keyframeEffectParam") { keyframeEffectParam(it) },
            ),
        )
    }

    private fun guarded(name: String, body: (List<Any?>) -> Any?): HostFunction = { args ->
        try {
            body(args)
        } catch (e: Exception) {
            throw IllegalArgumentException("$name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun addLog(raw: Any?): Any? {
        val text = (raw as? String ?: raw.toString()).take(MAX_LOG_CHARS)
        if (lines.size == MAX_LOG_LINES) lines += "(more log lines were dropped)"
        if (lines.size < MAX_LOG_LINES) lines += text
        return null
    }

    private fun layerOf(raw: Any?, write: Boolean): Layer {
        val id = (raw as? Number)?.toLong() ?: throw IllegalArgumentException("a layer id is needed")
        require(id in selection) { "the layer is not part of the selection" }
        val layer = pending.scene.layer(id) ?: throw IllegalArgumentException("the layer no longer exists")
        if (write) require(!layer.locked) { "the layer is locked" }
        return layer
    }

    private fun finite(raw: Any?, what: String): Double =
        (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$what must be a finite number")

    private fun describe(layer: Layer): Map<String, Any?> = mapOf(
        "id" to layer.id,
        "kind" to layer.kind,
        "name" to layer.label,
        "shape" to layer.shape,
        "start" to layer.startTime / 1000.0,
        "finish" to layer.endTime / 1000.0,
        "locked" to layer.locked,
    )

    private fun sizeOf(layer: Layer): Map<String, Any?>? =
        layer.prop("size")?.takeIf { it.size >= 2 }?.let { mapOf("width" to it[0].toDouble(), "height" to it[1].toDouble()) }

    private fun layerAt(layer: Layer, seconds: Any?): Layer =
        layer.at(layer.startTime + (finite(seconds, "the time") * 1000).roundToInt(), pending.scene.fps)

    private fun positionAt(layer: Layer, seconds: Any?): Map<String, Any?> {
        val at = layerAt(layer, seconds).location
        return mapOf("x" to at[0].toDouble(), "y" to at[1].toDouble())
    }

    private fun rotationAt(layer: Layer, seconds: Any?): Double = layerAt(layer, seconds).rotation.toDouble()

    private fun scaleAt(layer: Layer, seconds: Any?): Map<String, Any?> {
        val at = layerAt(layer, seconds).scale
        return mapOf("x" to at[0].toDouble(), "y" to at[1].toDouble())
    }

    private fun times(raw: Any?, layer: Layer): List<Float> {
        val list = raw as? List<*> ?: throw IllegalArgumentException("times must be a list of numbers")
        require(list.isNotEmpty()) { "times must not be empty" }
        val lengthMs = layer.endTime - layer.startTime
        require(lengthMs > 0) { "the layer has no length to put keyframes in" }
        var previous = -1.0
        return list.map { item ->
            val seconds = finite(item, "a time")
            require(seconds > previous) { "times must be in ascending order" }
            require(seconds >= -KEY_EPSILON && seconds * 1000 <= lengthMs + KEY_EPSILON * 1000) {
                "a keyframe at ${seconds}s is outside the layer, which is ${lengthMs / 1000.0}s long"
            }
            previous = seconds
            seconds.toFloat()
        }
    }

    private fun count(added: Int) {
        keyframes += added
        require(keyframes <= limits.maxKeyframes) { "too many keyframes (the limit is ${limits.maxKeyframes} for one run)" }
    }

    private fun point(raw: Any?, what: String): Pair<Float, Float> {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("$what must be {x, y}")
        return finite(map["x"], "$what.x").toFloat() to finite(map["y"], "$what.y").toFloat()
    }

    private fun valueText(property: String, raw: Any?, layer: Layer): String = when (property) {
        "position" -> point(raw, "a position").let { (x, y) -> formatFloats(floatArrayOf(x, y, layer.location[2])) }
        "scale" -> (if (raw is Number) finite(raw, "a scale").toFloat().let { it to it } else point(raw, "a scale"))
            .let { (x, y) -> formatFloats(floatArrayOf(x, y)) }
        "rotation" -> formatFloats(floatArrayOf(-finite(raw, "a rotation").toFloat()))
        else -> finite(raw, "an opacity").also { require(it in 0.0..1.0) { "an opacity must be between 0 and 1" } }
            .let { formatFloats(floatArrayOf(it.toFloat())) }
    }

    private fun setKeys(args: List<Any?>): Any? {
        val layer = layerOf(args.getOrNull(0), write = true)
        val property = args.getOrNull(1) as? String
        val ref = PROPERTIES[property] ?: throw IllegalArgumentException("the property must be one of ${PROPERTIES.keys.joinToString()}")
        val seconds = times(args.getOrNull(2), layer)
        val values = args.getOrNull(3) as? List<*>
        require(values != null && values.size == seconds.size) { "times and values need the same number of items" }
        count(seconds.size)
        val lengthMs = layer.endTime - layer.startTime
        val keys = seconds.zip(values).map { (second, raw) -> Keyframe((second * 1000f / lengthMs).coerceIn(0f, 1f), valueText(property!!, raw, layer)) }
        pending.scene = pending.scene.update(layer.id) { target ->
            target.withElement(ref, (target.element(ref) ?: XmlNode(ref.name)).withKeyframes(keys))
        }
        return null
    }

    private fun text(raw: Any?, what: String): String = when (raw) {
        is String -> raw
        is Boolean -> raw.toString()
        is Number -> raw.toString()
        else -> throw IllegalArgumentException("$what must be a number, text or true or false")
    }

    private fun name(raw: Any?, what: String): String = (raw as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$what must be text")

    private fun addEffect(args: List<Any?>): Any? {
        val layer = layerOf(args.getOrNull(0), write = true)
        val effect = name(args.getOrNull(1), "the effect id")
        val params = args.getOrNull(2) as? Map<*, *> ?: throw IllegalArgumentException("the parameters must be an object")
        val set = params.entries.associate { (key, value) -> (key as String) to text(value, "parameter '$key'") }
        pending.scene = pending.scene.update(layer.id, sampleMs) { ops.addEffect(it, effect, null, set) }
        return null
    }

    private fun setEffectParam(args: List<Any?>): Any? {
        val layer = layerOf(args.getOrNull(0), write = true)
        val effect = name(args.getOrNull(1), "the effect id")
        val param = name(args.getOrNull(2), "the parameter")
        val value = text(args.getOrNull(3), "the value")
        pending.scene = pending.scene.update(layer.id, sampleMs) { ops.setEffectParam(it, effect, param, value) }
        return null
    }

    private fun keyframeEffectParam(args: List<Any?>): Any? {
        val layer = layerOf(args.getOrNull(0), write = true)
        val effect = name(args.getOrNull(1), "the effect id")
        val param = name(args.getOrNull(2), "the parameter")
        val seconds = times(args.getOrNull(3), layer)
        val values = args.getOrNull(4) as? List<*>
        require(values != null && values.size == seconds.size) { "times and values need the same number of items" }
        count(seconds.size)
        val texts = values.map { text(it, "a value") }
        pending.scene = pending.scene.update(layer.id, sampleMs) { ops.keyframeEffectParam(it, effect, param, seconds, texts) }
        return null
    }
}
