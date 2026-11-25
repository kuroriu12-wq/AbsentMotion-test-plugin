package app.absentmotion.plugins.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class QuickJsSandboxTest {
    private class Recorder {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var pings = 0

        fun globals(): Map<String, Any?> = mapOf(
            "ctx" to mapOf<String, Any?>(
                "log" to { args: List<Any?> -> logs.add(args.firstOrNull().toString()); null },
                "ping" to { _: List<Any?> -> pings++; null },
                "limits" to mapOf("maxKeys" to 5),
            ),
        )
    }

    private fun run(
        source: String,
        budget: ScriptBudget = ScriptBudget(millis = 2000),
        globals: Map<String, Any?> = emptyMap(),
        cancelled: () -> Boolean = { false },
    ) = QuickJsSandbox(budget).run(source, "script", globals, cancelled)

    private fun returned(result: SandboxResult): Any? = (result as SandboxResult.Returned).value

    private fun failed(result: SandboxResult): SandboxResult.Failed {
        assertTrue("expected a failure, got $result", result is SandboxResult.Failed)
        return result as SandboxResult.Failed
    }

    private fun timed(block: () -> SandboxResult): Pair<SandboxResult, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    @Test fun hostFunctionsAndValuesWork() {
        val recorder = Recorder()
        val result = run("ctx.log('hello'); ctx.ping(); ctx.ping(); ctx.limits.maxKeys", globals = recorder.globals())
        assertEquals(5, (returned(result) as Number).toInt())
        assertEquals(2, recorder.pings)
        assertEquals(listOf("hello"), recorder.logs)
    }

    @Test fun hostFunctionsReceiveAndReturnData() {
        val globals = mapOf<String, Any?>(
            "host" to mapOf<String, Any?>(
                "sum" to { args: List<Any?> -> (args[0] as List<*>).sumOf { (it as Number).toDouble() } },
                "pair" to { args: List<Any?> -> mapOf("x" to (args[0] as Number).toDouble() * 2, "tags" to listOf("a", null, true)) },
            ),
        )
        val result = run("var p = host.pair(2); JSON.stringify([host.sum([1, 2, 3.5]), p.x, p.tags])", globals = globals)
        assertEquals("[6.5,4,[\"a\",null,true]]", returned(result))
    }

    @Test fun globalFunctionsAreSupported() {
        val globals = mapOf<String, Any?>("twice" to { args: List<Any?> -> (args[0] as Number).toInt() * 2 })
        assertEquals(42, (returned(run("twice(21)", globals = globals)) as Number).toInt())
    }

    @Test fun hostExceptionBecomesAScriptError() {
        val globals = mapOf<String, Any?>("fail" to { _: List<Any?> -> throw IllegalStateException("layer is locked") })
        val uncaught = failed(run("fail()", globals = globals))
        assertTrue(uncaught.reason, uncaught.reason.contains("layer is locked"))
        assertFalse(uncaught.interrupted)
        val caught = run("var r; try { fail(); } catch (e) { r = e.message; } r", globals = globals)
        assertEquals("layer is locked", returned(caught))
    }

    @Test fun nonFiniteHostResultIsRefused() {
        val globals = mapOf<String, Any?>("bad" to { _: List<Any?> -> Double.NaN })
        val result = failed(run("bad()", globals = globals))
        assertTrue(result.reason, result.reason.contains("not finite"))
    }

    @Test fun invalidHostNamesFailWithoutRunning() {
        val recorder = Recorder()
        val result = failed(run("ctx.ping()", globals = mapOf("bad name" to 1)))
        assertTrue(result.reason, result.reason.contains("bad name"))
        assertEquals(0, recorder.pings)
    }

    @Test fun noJavaOrAndroidIsReachable() {
        val names = listOf(
            "Packages", "java", "importClass", "require", "process", "setTimeout", "fetch", "Java", "android",
            "__spec",
        )
        val source = "[" + names.joinToString(",") { "typeof $it" } + "].join(',')"
        val value = returned(run(source)) as String
        assertEquals(names.joinToString(",") { "undefined" }, value)
    }

    @Test fun constructorEscapeFails() {
        val source = """
            var out = [];
            try { out.push(Function('return typeof java')()); } catch (e) { out.push('err'); }
            try { out.push((function () {}).constructor('return typeof Packages')()); } catch (e) { out.push('err'); }
            try { out.push((0, eval)('typeof java')); } catch (e) { out.push('err'); }
            out.join(',')
        """.trimIndent()
        assertEquals("undefined,undefined,undefined", returned(run(source)))
    }

    @Test fun rawBridgeObjectIsInert() {
        val recorder = Recorder()
        val source = """
            var out = [];
            try { out.push(String(__host.call('ctx.ping', '[]'))); } catch (e) { out.push('err'); }
            try { out.push(String(__host.call('wrong', 'ctx.ping', '[]'))); } catch (e) { out.push('err'); }
            out.join(',')
        """.trimIndent()
        returned(run(source, globals = recorder.globals()))
        assertEquals(0, recorder.pings)
    }

    @Test fun hostObjectsAreFrozen() {
        val recorder = Recorder()
        val source = """
            try { ctx.log = function () {}; } catch (e) {}
            try { Object.defineProperty(ctx, 'log', {value: 1}); } catch (e) {}
            try { delete ctx.log; } catch (e) {}
            try { ctx.extra = 1; } catch (e) {}
            try { ctx.limits.maxKeys = 99; } catch (e) {}
            try { ctx.__proto__ = {}; } catch (e) {}
            try { Object.prototype.polluted = 1; } catch (e) {}
            ctx.log('after');
            ctx.ping();
            [typeof ctx.log, typeof ctx.extra, ctx.limits.maxKeys, Object.isFrozen(ctx), Object.isFrozen(ctx.limits), Object.isFrozen(ctx.log)].join(',')
        """.trimIndent()
        val value = returned(run(source, globals = recorder.globals()))
        assertEquals("function,undefined,5,true,true,true", value)
        assertEquals(1, recorder.pings)
        assertEquals(listOf("after"), recorder.logs)
    }

    @Test fun infiniteLoopStopsWithinLimit() {
        val (result, ms) = timed { run("while (true) {}", ScriptBudget(millis = 1000)) }
        assertTrue(failed(result).interrupted)
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test fun loopThatCatchesStillStops() {
        val (result, ms) = timed { run("while (true) { try { for (;;) {} } catch (e) {} }", ScriptBudget(millis = 1000)) }
        assertTrue(failed(result).interrupted)
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test fun cancelStopsTheScript() {
        val flag = AtomicBoolean(false)
        Thread { Thread.sleep(150); flag.set(true) }.apply { isDaemon = true }.start()
        val (result, ms) = timed { run("while (true) {}", ScriptBudget(millis = 20_000), cancelled = { flag.get() }) }
        val failure = failed(result)
        assertTrue(failure.interrupted)
        assertEquals("the script was cancelled", failure.reason)
        assertTrue("took $ms ms", ms < 1500)
    }

    @Test fun interruptionIsReportedByClassWhateverTheMessageSays() {
        val failure = failed(run("while (true) {}", ScriptBudget(millis = 300)))
        assertTrue(failure.interrupted)
        assertTrue(failure.reason, failure.reason.contains("longer than"))
        assertFalse(failure.reason.contains("stack overflow", ignoreCase = true))
    }

    @Test fun deepRecursionFailsCleanly() {
        val (result, ms) = timed { run("function f() { return 1 + f(); } f()", ScriptBudget(millis = 5000)) }
        val failure = failed(result)
        assertFalse(failure.interrupted)
        assertTrue("took $ms ms", ms < 6000)
    }

    @Test fun errorTextIsTruncated() {
        val failure = failed(run("function f() { return 1 + f(); } f()", ScriptBudget(millis = 5000)))
        assertTrue("reason has ${failure.reason.length} characters", failure.reason.length <= 300)
        assertFalse(failure.reason.contains("\n"))
    }

    @Test fun hugeAllocationsAreRefused() {
        val budget = ScriptBudget(millis = 8000, memoryBytes = 64L * 1024 * 1024)
        val (string, stringMs) = timed { run("var s = 'x'.repeat(1e9); s.length", budget) }
        val stringFailure = failed(string)
        assertTrue(stringFailure.reason, stringFailure.reason.contains("memory"))
        assertFalse(stringFailure.interrupted)
        assertTrue("took $stringMs ms", stringMs < 4000)
        val (flood, floodMs) = timed { run("var a = []; while (true) { a.push('y'.repeat(100000)); }", budget) }
        val floodFailure = failed(flood)
        assertTrue(floodFailure.reason, floodFailure.reason.contains("memory"))
        assertTrue("took $floodMs ms", floodMs < 6000)
    }

    @Test fun errorsCarryTheLine() {
        val failure = failed(run("var a = 1;\nvar b = 2;\nthrow new Error('boom');"))
        assertEquals(3, failure.line)
        assertTrue(failure.reason, failure.reason.contains("boom"))
    }

    @Test fun syntaxErrorIsReadable() {
        val failure = failed(run("var = ;"))
        assertTrue(failure.reason.isNotBlank())
        assertFalse(failure.interrupted)
    }

    @Test fun errorThrownByNonErrorValueIsReadable() {
        val failure = failed(run("throw 'plain text';"))
        assertTrue(failure.reason.isNotBlank())
        assertNull(failure.line)
    }

    @Test fun globalsDoNotLeakBetweenRuns() {
        run("leak = 1; var other = 2; 1")
        assertEquals("undefined,undefined", returned(run("[typeof leak, typeof other].join(',')")))
    }

    @Test fun hostGlobalsDoNotLeakBetweenRuns() {
        val recorder = Recorder()
        run("ctx.ping()", globals = recorder.globals())
        assertEquals("undefined", returned(run("typeof ctx")))
    }

    @Test fun completionValueThatIsAnObjectDoesNotFail() {
        val result = run("({a: 1, b: [1, 2]})")
        assertTrue(result.toString(), result is SandboxResult.Returned)
    }

    @Test fun languageFeatures() {
        val source = """
            var f = {};
            function t(n, c) { try { f[n] = !!c(); } catch (e) { f[n] = false; } }
            t('arrow', function () { return eval('(() => 1)()') === 1; });
            t('let/const', function () { return eval('let a = 1; const b = 2; a + b') === 3; });
            t('template', function () { return eval('`a${'$'}{1}`') === 'a1'; });
            t('spread', function () { return eval('Math.max(...[1, 2])') === 2; });
            t('destructuring', function () { return eval('var {a} = {a: 1}; a') === 1; });
            t('class', function () { return eval('class A {}; typeof A') === 'function'; });
            t('for-of', function () { return eval('var s = 0; for (var x of [1, 2]) s += x; s') === 3; });
            t('default-param', function () { return eval('(function (a = 2) { return a; })()') === 2; });
            t('Map/Set', function () { return typeof Map === 'function' && typeof Set === 'function'; });
            t('Promise', function () { return typeof Promise === 'function'; });
            t('Math.hypot', function () { return Math.hypot(3, 4) === 5; });
            t('Float64Array', function () { return typeof Float64Array === 'function'; });
            JSON.stringify(f)
        """.trimIndent()
        val features = org.json.JSONObject(returned(run(source)) as String)
        val missing = features.keys().asSequence().filter { !features.getBoolean(it) }.toList()
        assertTrue("missing: $missing", missing.isEmpty())
        assertNotNull(features.opt("class"))
    }

    @Test fun benchmarkFitsTheDefaultBudget() {
        val source = """
            function bench() {
                var N = 30, steps = 1200, dt = 1 / 120, g = 3 * 1080;
                var x = [], y = [], vx = [], vy = [], r = [];
                for (var i = 0; i < N; i++) {
                    x.push(100 + (i * 37) % 900); y.push(100 + (i * 53) % 800);
                    vx.push((i % 7) - 3); vy.push(0); r.push(30 + (i % 5) * 4);
                }
                var hits = 0;
                for (var s = 0; s < steps; s++) {
                    for (var i = 0; i < N; i++) {
                        vy[i] += g * dt; x[i] += vx[i] * dt; y[i] += vy[i] * dt;
                        if (y[i] + r[i] > 1080) { y[i] = 1080 - r[i]; vy[i] *= -0.5; }
                    }
                    for (var i = 0; i < N; i++) {
                        for (var j = i + 1; j < N; j++) {
                            var dx = x[j] - x[i], dy = y[j] - y[i], rr = r[i] + r[j];
                            if (dx * dx + dy * dy < rr * rr) hits++;
                        }
                    }
                }
                return hits;
            }
            bench()
        """.trimIndent()
        val (result, ms) = timed { run(source, ScriptBudget()) }
        assertTrue(result.toString(), result is SandboxResult.Returned)
        println("benchmark: ${ms} ms")
        assertTrue("took $ms ms", ms < 10_000)
    }
}
