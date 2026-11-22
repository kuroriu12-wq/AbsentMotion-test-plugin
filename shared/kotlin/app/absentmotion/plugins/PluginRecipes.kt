package app.absentmotion.plugins

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
