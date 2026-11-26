package app.absentmotion.plugins.script

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.plugins.InputProblem
import app.absentmotion.plugins.parseNumber
import app.absentmotion.plugins.resolveInputs

sealed interface ScriptResult {
    data class Done(val scene: Scene, val log: List<String>) : ScriptResult

    data class Failed(val reason: String, val line: Int?, val log: List<String>) : ScriptResult
}

class ScriptRunner(private val catalog: EffectCatalog, private val engine: () -> ScriptEngine) {
    fun run(
        scene: Scene,
        selection: List<Long>,
        script: PluginScript,
        inputs: Map<String, String>,
        sampleMs: Int,
        cancelled: () -> Boolean = { false },
    ): ScriptResult {
        if (selection.isEmpty()) return ScriptResult.Failed("select at least one layer first", null, emptyList())
        val values = try {
            resolveInputs(script.inputs, inputs)
        } catch (e: InputProblem) {
            return ScriptResult.Failed(e.message ?: "the inputs are not valid", null, emptyList())
        }
        val typed = script.inputs.associate { input ->
            val raw = values.getValue(input.id)
            input.id to when (input.type) {
                "number" -> parseNumber(raw).toDouble()
                "switch" -> raw == "true"
                else -> raw
            }
        }
        val pending = PendingScene(scene)
        val host = ScriptHost(pending, selection, sampleMs, typed, catalog)
        val globals = try {
            host.context()
        } catch (e: IllegalArgumentException) {
            return ScriptResult.Failed(e.message ?: "the script could not start", null, emptyList())
        }
        return when (val result = engine().run(runnableSource(script.source), script.path, globals, cancelled)) {
            is SandboxResult.Returned -> ScriptResult.Done(pending.scene, host.log.toList())
            is SandboxResult.Failed -> ScriptResult.Failed(result.reason, result.line, host.log.toList())
        }
    }
}
