package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import org.json.JSONObject
import java.io.File

object PluginEffects {
    fun read(plugin: PluginStore.Installed, language: String): List<EffectDefinition> {
        val manifest = plugin.manifest ?: return emptyList()
        if (!plugin.enabled || plugin.problem != null) return emptyList()
        val folder = File(plugin.dir, "effects")
        return manifest.effects.mapNotNull { key -> runCatching { load(manifest.id, key, folder, language) }.getOrNull() }
    }

    private fun load(pluginId: String, key: String, folder: File, language: String): EffectDefinition? {
        if (JSONObject(File(folder, "$key.json").readText()).optBoolean("native")) return null
        val parsed = EffectCatalog.parse(language) { name ->
            if (name == "index.json") "[${JSONObject.quote(key)}]".byteInputStream() else File(folder, name).inputStream()
        }.definitions.singleOrNull()?.takeIf { it.key == key } ?: return null
        val id = "$pluginId.$key"
        return parsed.copy(id = id, key = id, page = id, since = null, shortcuts = emptyList(), alsoIn = emptyList(), plugin = pluginId)
    }
}

fun EffectCatalog.withPlugins(plugins: List<PluginStore.Installed>, language: String): EffectCatalog {
    val extra = plugins.flatMap { PluginEffects.read(it, language) }.distinctBy { it.id }.filter { added -> definitions.none { it.id == added.id } }
    return if (extra.isEmpty()) this else copy(definitions = definitions + extra)
}
