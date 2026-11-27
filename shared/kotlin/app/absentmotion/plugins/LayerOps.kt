package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.withKeyframes

private const val KeyEpsilon = 1e-4f

private val ColourPattern = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

internal fun parseNumber(raw: String): Float {
    val number = raw.trim().toFloatOrNull()
    if (number == null || !number.isFinite()) throw IllegalArgumentException("'$raw' is not a number")
    return number
}

internal fun requireColour(raw: String) = require(ColourPattern.matches(raw)) { "'$raw' is not a colour like #rrggbb" }

internal class InputProblem(message: String) : Exception(message)

internal fun resolveInputs(inputs: List<RecipeInput>, given: Map<String, String>): Map<String, String> = inputs.associate { input ->
    val raw = given[input.id] ?: input.default
    try {
        checkInput(input, raw)
    } catch (e: IllegalArgumentException) {
        throw InputProblem("${input.label}: ${e.message}")
    }
    input.id to raw
}

private fun checkInput(input: RecipeInput, raw: String) {
    when (input.type) {
        "number" -> {
            val number = parseNumber(raw)
            input.min?.let { require(number >= it) { "must be at least $it" } }
            input.max?.let { require(number <= it) { "must be at most $it" } }
        }
        "switch" -> require(raw == "true" || raw == "false") { "must be true or false" }
        "choice" -> require(raw in input.options) { "must be one of ${input.options.joinToString()}" }
        "color" -> requireColour(raw)
    }
}

class LayerOps(private val catalog: EffectCatalog) {
    fun addEffect(layer: Layer, effect: String, preset: String?, set: Map<String, String>): Layer {
        val definition = definition(effect)
        val chosen = preset?.let { name ->
            definition.presets.firstOrNull { it.name == name || it.slug == name }
                ?: throw IllegalArgumentException("${definition.id} has no preset '$name'")
        }
        var instance = definition.instance(chosen, layer.endTime - layer.startTime)
        for ((paramId, raw) in set) {
            val param = parameter(definition, paramId)
            instance = instance.withEffectProperty(param.id, param.type, format(param, raw), layer.t, layer.snap)
        }
        return layer.addEffect(instance)
    }

    fun setEffectParam(layer: Layer, effect: String, paramId: String, raw: String): Layer {
        val definition = definition(effect)
        val param = parameter(definition, paramId)
        val index = lastIndexOf(layer, effect)
        val value = format(param, raw)
        return layer.withEffect(index) { it.withEffectProperty(param.id, param.type, value, layer.t, layer.snap) }
    }

    fun keyframeEffectParam(layer: Layer, effect: String, paramId: String, at: List<Float>, values: List<String>): Layer {
        val definition = definition(effect)
        val param = parameter(definition, paramId)
        val index = lastIndexOf(layer, effect)
        require(at.size == values.size && at.isNotEmpty()) { "keyframes need the same number of times and values" }
        val lengthMs = layer.endTime - layer.startTime
        require(lengthMs > 0) { "the layer has no length to put keyframes in" }
        val keys = at.zip(values).map { (seconds, raw) ->
            val t = seconds * 1000f / lengthMs
            require(t in -KeyEpsilon..(1f + KeyEpsilon)) { "a keyframe at ${seconds}s is outside the layer, which is ${lengthMs / 1000f}s long" }
            Keyframe(t.coerceIn(0f, 1f), format(param, raw))
        }
        return layer.withEffect(index) { instance ->
            val old = instance.effectProperty(param.id)
            val property = (old ?: XmlNode("property", mapOf("name" to param.id, "type" to param.type))).withKeyframes(keys)
            instance.copy(children = if (old == null) instance.children + property else instance.children.map { if (it === old) property else it })
        }
    }

    private fun definition(id: String): EffectDefinition = catalog.find(id) ?: throw IllegalArgumentException("unknown effect '$id'")

    private fun parameter(definition: EffectDefinition, id: String): EffectParam =
        definition.params.firstOrNull { it.id == id } ?: throw IllegalArgumentException("${definition.id} has no parameter '$id'")

    private fun lastIndexOf(layer: Layer, effect: String): Int {
        val index = layer.effects.indexOfLast { it["id"] == effect }
        require(index >= 0) { "the layer has no $effect effect yet" }
        return index
    }

    private fun format(param: EffectParam, raw: String): String = when (param.kind) {
        "float", "angle" -> param.format(parseNumber(raw))
        "choice" -> {
            val named = param.options.indexOf(raw)
            val index = if (named >= 0) named else parseNumber(raw).let { n -> n.toInt().also { require(it.toFloat() == n) { "'$raw' is not a whole number" } } }
            require(index in param.options.indices) { "'$raw' is not one of ${param.options.joinToString()}" }
            param.format(index.toFloat())
        }
        "switch" -> {
            require(raw == "true" || raw == "false") { "'$raw' is not true or false" }
            param.format(raw == "true")
        }
        "color" -> {
            requireColour(raw)
            param.format(raw)
        }
        "text" -> param.format(raw)
        "point" -> {
            val parts = raw.split(',').map { parseNumber(it) }
            require(parts.size == 2) { "'$raw' is not a point like x,y" }
            formatFloats(parts.toFloatArray())
        }
        else -> throw IllegalArgumentException("${param.id} has an unsupported type '${param.kind}'")
    }
}
