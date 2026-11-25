package app.absentmotion.plugins.script

import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.RecipeInput
import app.absentmotion.plugins.parseRecipeInputs
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class PluginScript(
    val plugin: PluginStore.Installed,
    val path: String,
    val id: String,
    val name: String,
    val inputs: List<RecipeInput>,
    val source: String,
)

data class ScriptListing(val scripts: List<PluginScript>, val problems: Map<String, String>)

val ScriptLoadBudget = ScriptBudget(millis = 3_000)

private const val MAX_SOURCE_BYTES = 512 * 1024

private val ScriptId = Regex("[A-Za-z_][A-Za-z0-9_]*")

private const val DEFINE_PRELUDE =
    "var console={log:function(){}};" +
        "var defineScript=function(spec){" +
        "if(spec===null||typeof spec!=='object')throw new Error('defineScript needs an object');" +
        "if(typeof spec.run!=='function')throw new Error('defineScript needs a run function');" +
        "__defined(JSON.stringify({id:spec.id,name:spec.name,inputs:spec.inputs}));};"

private class Defined(val id: String, val name: String, val inputs: List<RecipeInput>)

private fun parseDefined(record: String): Pair<Defined?, List<String>> {
    val o = try {
        JSONObject(record)
    } catch (e: JSONException) {
        return null to listOf("defineScript did not receive valid data")
    }
    val problems = ArrayList<String>()
    val id = (o.opt("id") as? String)?.takeIf { ScriptId.matches(it) }
    if (id == null) problems += "id must start with a letter or underscore and use letters, digits and underscores"
    val name = (o.opt("name") as? String)?.takeIf { it.isNotBlank() }
    if (name == null) problems += "name is required"
    val inputs = parseRecipeInputs(o, problems)
    if (problems.isNotEmpty() || id == null || name == null) return null to problems
    return Defined(id, name, inputs) to emptyList()
}

private fun load(source: String, path: String, engine: ScriptEngine): Pair<Defined?, String?> {
    val records = ArrayList<String>()
    val globals = mapOf<String, Any?>(
        "__defined" to { args: List<Any?> ->
            records += (args.firstOrNull() as? String).orEmpty()
            null
        },
    )
    when (val result = engine.run(DEFINE_PRELUDE + source, path, globals)) {
        is SandboxResult.Failed -> {
            val where = result.line?.let { " (line $it)" }.orEmpty()
            return null to result.reason + where
        }
        is SandboxResult.Returned -> Unit
    }
    if (records.isEmpty()) return null to "the script never calls defineScript"
    if (records.size > 1) return null to "the script calls defineScript more than once"
    val (defined, problems) = parseDefined(records.single())
    return defined to problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

fun loadScripts(store: PluginStore, engine: ScriptEngine = QuickJsSandbox(ScriptLoadBudget)): ScriptListing {
    val scripts = ArrayList<PluginScript>()
    val problems = LinkedHashMap<String, String>()
    for (plugin in store.enabled()) {
        val manifest = plugin.manifest ?: continue
        val seen = HashSet<String>()
        for (path in manifest.scripts) {
            val key = "${plugin.id}/$path"
            val file = File(plugin.dir, path)
            val source = try {
                if (file.length() > MAX_SOURCE_BYTES) {
                    problems[key] = "the script is larger than ${MAX_SOURCE_BYTES / 1024} KB"
                    continue
                }
                file.readText().removePrefix("\uFEFF")
            } catch (e: Exception) {
                problems[key] = "the script could not be read: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            val (defined, problem) = load(source, path, engine)
            if (defined == null) {
                problems[key] = problem ?: "the script could not be loaded"
            } else if (!seen.add(defined.id)) {
                problems[key] = "another script in this plugin already uses the id '${defined.id}'"
            } else {
                scripts += PluginScript(plugin, path, defined.id, defined.name, defined.inputs, source)
            }
        }
    }
    return ScriptListing(scripts, problems)
}

fun listScripts(store: PluginStore, engine: ScriptEngine = QuickJsSandbox(ScriptLoadBudget)): List<PluginScript> =
    loadScripts(store, engine).scripts

fun scriptProblems(store: PluginStore, engine: ScriptEngine = QuickJsSandbox(ScriptLoadBudget)): Map<String, String> =
    loadScripts(store, engine).problems
