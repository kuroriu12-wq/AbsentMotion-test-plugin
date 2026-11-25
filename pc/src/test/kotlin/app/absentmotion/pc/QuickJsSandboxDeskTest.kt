package app.absentmotion.pc

import app.absentmotion.plugins.script.QuickJsSandbox
import app.absentmotion.plugins.script.SandboxResult
import app.absentmotion.plugins.script.ScriptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJsSandboxDeskTest {
    private fun run(source: String, budget: ScriptBudget = ScriptBudget(millis = 2000), globals: Map<String, Any?> = emptyMap()) =
        QuickJsSandbox(budget).run(source, "script", globals)

    @Test fun scriptRunsAndCallsTheHost() {
        val seen = mutableListOf<String>()
        val globals = mapOf<String, Any?>("ctx" to mapOf<String, Any?>("log" to { args: List<Any?> -> seen.add(args[0].toString()); null }))
        val result = run("ctx.log('hi'); 1 + 2", globals = globals) as SandboxResult.Returned
        assertEquals(3, (result.value as Number).toInt())
        assertEquals(listOf("hi"), seen)
    }

    @Test fun infiniteLoopIsStopped() {
        val start = System.nanoTime()
        val result = run("while (true) {}", ScriptBudget(millis = 500)) as SandboxResult.Failed
        assertTrue(result.interrupted)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test fun memoryLimitHolds() {
        val result = run("'x'.repeat(1e9).length", ScriptBudget(millis = 8000, memoryBytes = 64L * 1024 * 1024)) as SandboxResult.Failed
        assertTrue(result.reason, result.reason.contains("memory"))
        assertFalse(result.interrupted)
    }

    @Test fun javaIsNotReachable() {
        val result = run("[typeof Packages, typeof java, typeof require, typeof process].join(',')") as SandboxResult.Returned
        assertEquals("undefined,undefined,undefined,undefined", result.value)
    }
}
