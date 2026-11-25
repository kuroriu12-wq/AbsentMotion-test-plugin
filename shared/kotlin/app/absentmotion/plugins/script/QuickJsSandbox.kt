package app.absentmotion.plugins.script

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_REASON = 300

private val NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

private const val BOOTSTRAP = """
(function () {
    var host = globalThis.__host;
    var parse = JSON.parse;
    var stringify = JSON.stringify;
    var keys = Object.keys;
    var freezeObject = Object.freeze;
    var hasOwn = Object.prototype.hasOwnProperty;
    var slice = Array.prototype.slice;
    var spec = parse(globalThis.__spec);
    delete globalThis.__host;
    delete globalThis.__spec;
    function wrap(path) {
        return function () {
            var reply = parse(host.call(path, stringify(slice.call(arguments))));
            if (hasOwn.call(reply, 'e')) throw new Error(reply.e);
            return reply.v;
        };
    }
    function freeze(value) {
        if (value !== null && typeof value === 'object') {
            keys(value).forEach(function (key) { freeze(value[key]); });
            freezeObject(value);
        }
        return value;
    }
    keys(spec.data).forEach(function (name) { globalThis[name] = spec.data[name]; });
    spec.functions.forEach(function (path) {
        var target = globalThis;
        for (var i = 0; i < path.length - 1; i++) {
            if (target[path[i]] === undefined) target[path[i]] = {};
            target = target[path[i]];
        }
        target[path[path.length - 1]] = freezeObject(wrap(path.join('.')));
    });
    keys(spec.data).concat(spec.functions.map(function (path) { return path[0]; })).forEach(function (name) {
        freeze(globalThis[name]);
    });
})();
"""

private fun toJson(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is Boolean, is String -> value
    is Number -> {
        require(value.toDouble().isFinite()) { "a number that is not finite cannot be passed to the script" }
        value
    }
    is List<*> -> JSONArray().also { array -> value.forEach { array.put(toJson(it)) } }
    is Map<*, *> -> JSONObject().also { obj -> value.forEach { (key, item) -> obj.put(key as String, toJson(item)) } }
    else -> throw IllegalArgumentException("a ${value.javaClass.simpleName} cannot be passed to the script")
}

private fun fromJson(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONArray -> (0 until value.length()).map { fromJson(value.get(it)) }
    is JSONObject -> value.keys().asSequence().associateWith { fromJson(value.get(it)) }
    else -> value
}

@Suppress("UNCHECKED_CAST")
private fun asHost(value: Function1<*, *>): HostFunction = value as HostFunction

private class Prepared(val spec: String, val functions: Map<String, HostFunction>)

private fun prepare(globals: Map<String, Any?>): Prepared {
    val functions = LinkedHashMap<String, HostFunction>()
    val paths = JSONArray()
    val data = JSONObject()

    fun split(prefix: List<String>, value: Any?): Any? = when (value) {
        is Function1<*, *> -> {
            functions[prefix.joinToString(".")] = asHost(value)
            paths.put(JSONArray(prefix))
            null
        }
        is Map<*, *> -> {
            val kept = JSONObject()
            value.forEach { (key, item) ->
                val name = key as String
                require(NAME.matches(name)) { "'$name' is not a usable name in the script" }
                val rest = split(prefix + name, item)
                if (rest != null) kept.put(name, rest)
            }
            if (kept.length() == 0 && value.isNotEmpty()) null else kept
        }
        else -> toJson(value)
    }

    for ((name, value) in globals) {
        require(NAME.matches(name)) { "'$name' is not a usable name in the script" }
        val rest = split(listOf(name), value)
        if (rest != null) data.put(name, rest)
    }
    return Prepared(JSONObject().put("data", data).put("functions", paths).toString(), functions)
}

private fun clip(message: String?, fallback: String): String {
    val first = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { fallback }
    return if (first.length > MAX_REASON) first.take(MAX_REASON - 3) + "..." else first
}

class QuickJsSandbox(private val budget: ScriptBudget = ScriptBudget()) : ScriptEngine {
    override fun run(source: String, chunkName: String, globals: Map<String, Any?>, cancelled: () -> Boolean): SandboxResult {
        val prepared = try {
            prepare(globals)
        } catch (e: Exception) {
            return SandboxResult.Failed(clip(e.message, "the host values are not valid"), null, false)
        }
        val stoppedByCancel = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        return try {
            val value = runBlocking {
                quickJs {
                    evaluationTimeoutMillis = budget.millis
                    memoryLimit = budget.memoryBytes
                    maxStackSize = budget.stackBytes
                    val instance = this
                    val watcher = Thread {
                        while (!finished.get()) {
                            if (cancelled()) {
                                stoppedByCancel.set(true)
                                instance.interruptEvaluation()
                                break
                            }
                            try {
                                Thread.sleep(10)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    watcher.isDaemon = true
                    watcher.start()
                    try {
                        define("__host") {
                            function("call") { args ->
                                val path = args[0] as String
                                val reply = JSONObject()
                                try {
                                    val callArgs = fromJson(JSONArray(args[1] as String)) as List<*>
                                    val function = prepared.functions.getValue(path)
                                    reply.put("v", toJson(function(callArgs)))
                                } catch (e: Throwable) {
                                    reply.put("e", clip(e.message, e.javaClass.simpleName))
                                }
                                reply.toString()
                            }
                        }
                        evaluate<Any?>("globalThis.__spec = ${JSONObject.quote(prepared.spec)};", filename = "host")
                        evaluate<Any?>(BOOTSTRAP, filename = "host")
                        evaluate<Any?>(source, filename = chunkName)
                    } finally {
                        finished.set(true)
                    }
                }
            }
            SandboxResult.Returned(value)
        } catch (e: QuickJsInterruptedException) {
            SandboxResult.Failed(
                if (stoppedByCancel.get()) "the script was cancelled" else "the script ran longer than ${budget.millis / 1000.0} seconds and was stopped",
                null,
                true,
            )
        } catch (e: QuickJsException) {
            val message = e.message.orEmpty()
            val reason = if (message.contains("out of memory", ignoreCase = true)) {
                "the script used more than ${budget.memoryBytes / (1024 * 1024)} MB of memory and was stopped"
            } else {
                clip(message, "the script failed")
            }
            SandboxResult.Failed(reason, e.lineNumber?.toInt(), false)
        } catch (e: Throwable) {
            SandboxResult.Failed(clip(e.message, e.javaClass.simpleName), null, false)
        }
    }
}
