package app.absentmotion.plugins.script

data class ScriptBudget(
    val millis: Long = 15_000,
    val memoryBytes: Long = 64L * 1024 * 1024,
    val stackBytes: Long = 256L * 1024,
)

sealed interface SandboxResult {
    data class Returned(val value: Any?) : SandboxResult

    data class Failed(val reason: String, val line: Int?, val interrupted: Boolean) : SandboxResult
}

typealias HostFunction = (List<Any?>) -> Any?

interface ScriptEngine {
    fun run(
        source: String,
        chunkName: String,
        globals: Map<String, Any?>,
        cancelled: () -> Boolean = { false },
    ): SandboxResult
}
