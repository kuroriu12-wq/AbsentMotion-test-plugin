package app.absentmotion.plugins

import app.absentmotion.plugins.script.PluginScript
import app.absentmotion.plugins.script.QuickJsSandbox
import app.absentmotion.plugins.script.ScriptEngine
import app.absentmotion.plugins.script.ScriptLoadBudget
import app.absentmotion.plugins.script.loadScripts
import java.io.File

data class PluginRecipe(val plugin: PluginStore.Installed, val recipe: Recipe)

fun listRecipes(store: PluginStore): List<PluginRecipe> = store.enabled().flatMap { plugin ->
    val manifest = plugin.manifest ?: return@flatMap emptyList()
    manifest.recipes.mapNotNull { path ->
        runCatching { parseRecipe(File(plugin.dir, path).readText()) }.getOrNull()
            ?.let { it as? RecipeParse.Valid }
            ?.let { PluginRecipe(plugin, it.recipe) }
    }
}

sealed interface PluginTool {
    val plugin: PluginStore.Installed
    val name: String
    val description: String
    val inputs: List<RecipeInput>

    data class OfRecipe(val entry: PluginRecipe) : PluginTool {
        override val plugin get() = entry.plugin
        override val name get() = entry.recipe.name
        override val description get() = entry.recipe.description
        override val inputs get() = entry.recipe.inputs
    }

    data class OfScript(val script: PluginScript) : PluginTool {
        override val plugin get() = script.plugin
        override val name get() = script.name
        override val description get() = script.description
        override val inputs get() = script.inputs
    }
}

fun listTools(store: PluginStore, engine: ScriptEngine = QuickJsSandbox(ScriptLoadBudget)): List<PluginTool> =
    listRecipes(store).map { PluginTool.OfRecipe(it) } + loadScripts(store, engine).scripts.map { PluginTool.OfScript(it) }

fun hasTools(store: PluginStore): Boolean =
    listRecipes(store).isNotEmpty() || store.enabled().any { it.manifest?.scripts?.isNotEmpty() == true }

private const val MAX_HELP_CHARS = 20_000

fun pluginHelp(plugin: PluginStore.Installed): String? = try {
    File(plugin.dir, "help.md").takeIf { it.isFile }?.readText()?.removePrefix("\uFEFF")?.trim()?.take(MAX_HELP_CHARS)?.takeIf { it.isNotEmpty() }
} catch (e: java.io.IOException) {
    null
}
