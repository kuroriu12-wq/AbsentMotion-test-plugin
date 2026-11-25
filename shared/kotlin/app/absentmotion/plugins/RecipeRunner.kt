package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene

sealed interface RunResult {
    data class Done(val scene: Scene) : RunResult

    data class Failed(val stepIndex: Int?, val reason: String) : RunResult
}

private class InputProblem(message: String) : Exception(message)

class RecipeRunner(catalog: EffectCatalog) {
    private val ops = LayerOps(catalog)

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
        is RecipeStep.AddEffect -> ops.addEffect(layer, step.effect, step.preset, step.set.mapValues { (_, raw) -> substitute(raw, values) })
        is RecipeStep.SetEffectParam -> ops.setEffectParam(layer, step.effect, step.param, substitute(step.value, values))
        is RecipeStep.KeyframeEffectParam ->
            ops.keyframeEffectParam(layer, step.effect, step.param, step.at, step.values.map { substitute(it, values) })
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
                val number = parseNumber(raw)
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
}
