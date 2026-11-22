package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Keyframe
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.XmlNode
import app.absentmotion.model.addEffect
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.formatFloats
import app.absentmotion.model.withEffect
import app.absentmotion.model.withEffectProperty
import app.absentmotion.model.withKeyframes

sealed interface RunResult {
    data class Done(val scene: Scene) : RunResult

    data class Failed(val stepIndex: Int?, val reason: String) : RunResult
}

private class InputProblem(message: String) : Exception(message)

class RecipeRunner(private val catalog: EffectCatalog) {
    fun run(scene: Scene, layerId: Long, recipe: Recipe, inputs: Map<String, String>, sampleMs: Int): RunResult {
        val values = try {
            resolve(recipe, inputs)
        } catch (e: InputProblem) {
            return RunResult.Failed(null, e.message ?: "the inputs are not valid")
        }
        val layer = scene.layer(layerId) ?: return RunResult.Failed(null, "the layer was not found")
        if (layer.locked) return RunResult.Failed(null, "the layer is locked")

        var failedAt = -1
        return try {
            RunResult.Done(
                scene.update(layerId, sampleMs) { target ->
                    recipe.steps.foldIndexed(target) { index, current, step ->
                        failedAt = index
                        apply(current, step, values)
                    }
                },
            )
        } catch (e: Exception) {
            RunResult.Failed(failedAt.takeIf { it >= 0 }, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun apply(layer: Layer, step: RecipeStep, values: Map<String, String>): Layer = when (step) {
        is RecipeStep.AddEffect -> {
            val definition = definition(step.effect)
            val preset = step.preset?.let { name ->
                definition.presets.firstOrNull { it.name == name || it.slug == name }
                    ?: throw IllegalArgumentException("${definition.id} has no preset '$name'")
            }
            var instance = definition.instance(preset, layer.endTime - layer.startTime)
            for ((paramId, raw) in step.set) {
                val param = parameter(definition, paramId)
                instance = instance.withEffectProperty(param.id, param.type, format(param, substitute(raw, values)), layer.t, layer.snap)
            }
            layer.addEffect(instance)
        }
        is RecipeStep.SetEffectParam -> {
            val definition = definition(step.effect)
            val param = parameter(definition, step.param)
            val index = lastIndexOf(layer, step.effect)
            val value = format(param, substitute(step.value, values))
            layer.withEffect(index) { it.withEffectProperty(param.id, param.type, value, layer.t, layer.snap) }
        }
        is RecipeStep.KeyframeEffectParam -> {
            val definition = definition(step.effect)
            val param = parameter(definition, step.param)
            val index = lastIndexOf(layer, step.effect)
            require(step.at.size == step.values.size && step.at.isNotEmpty()) { "keyframes need the same number of times and values" }
            val lengthMs = layer.endTime - layer.startTime
            require(lengthMs > 0) { "the layer has no length to put keyframes in" }
            val keys = step.at.zip(step.values).map { (seconds, raw) ->
                val t = seconds * 1000f / lengthMs
                require(t in -KeyEpsilon..(1f + KeyEpsilon)) { "a keyframe at ${seconds}s is outside the layer, which is ${lengthMs / 1000f}s long" }
                Keyframe(t.coerceIn(0f, 1f), format(param, substitute(raw, values)))
            }
            layer.withEffect(index) { effect ->
                val old = effect.effectProperty(param.id)
                val property = (old ?: XmlNode("property", mapOf("name" to param.id, "type" to param.type))).withKeyframes(keys)
                effect.copy(children = if (old == null) effect.children + property else effect.children.map { if (it === old) property else it })
            }
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

    private fun resolve(recipe: Recipe, given: Map<String, String>): Map<String, String> = recipe.inputs.associate { input ->
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
                val number = number(raw)
                input.min?.let { require(number >= it) { "must be at least $it" } }
                input.max?.let { require(number <= it) { "must be at most $it" } }
            }
            "switch" -> require(raw == "true" || raw == "false") { "must be true or false" }
            "choice" -> require(raw in input.options) { "must be one of ${input.options.joinToString()}" }
            "color" -> requireColour(raw)
        }
    }

    private fun substitute(raw: String, values: Map<String, String>): String =
        if (raw.startsWith("$")) (values[raw.drop(1)] ?: throw IllegalArgumentException("'$raw' is not an input of this recipe")) else raw

    private fun format(param: EffectParam, raw: String): String = when (param.kind) {
        "float", "angle" -> param.format(number(raw))
        "choice" -> {
            val named = param.options.indexOf(raw)
            val index = if (named >= 0) named else number(raw).let { n -> n.toInt().also { require(it.toFloat() == n) { "'$raw' is not a whole number" } } }
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
            val parts = raw.split(',').map { number(it) }
            require(parts.size == 2) { "'$raw' is not a point like x,y" }
            formatFloats(parts.toFloatArray())
        }
        else -> throw IllegalArgumentException("${param.id} has an unsupported type '${param.kind}'")
    }

    private fun number(raw: String): Float {
        val number = raw.trim().toFloatOrNull()
        if (number == null || !number.isFinite()) throw IllegalArgumentException("'$raw' is not a number")
        return number
    }

    private fun requireColour(raw: String) = require(ColourPattern.matches(raw)) { "'$raw' is not a colour like #rrggbb" }

    private companion object {
        const val KeyEpsilon = 1e-4f
        val ColourPattern = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
    }
}
