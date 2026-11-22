package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import org.json.JSONObject
import java.io.File

/**
 * Effect packs: the shader effects a plugin ships in its `effects/` folder, in the same format as the built-in ones.
 * Plugin effects are plain shader effects, so they need no native code; the engine loads them like any other effect.
 */
object PluginEffects {
    /**
     * The effects [plugin] contributes, or nothing when it is disabled or broken. An effect that fails to load (not
     * JSON, no shader, unknown category, a `native` flag, an id that does not match its key) is left out and the
     * plugin's other effects still load.
     */
    fun read(plugin: PluginStore.Installed, language: String): List<EffectDefinition> {
        val manifest = plugin.manifest ?: return emptyList()
        if (!plugin.enabled || plugin.problem != null) return emptyList()
        val folder = File(plugin.dir, "effects")
        return manifest.effects.mapNotNull { key -> runCatching { load(manifest.id, key, folder, language) }.getOrNull() }
    }

    private fun load(pluginId: String, key: String, folder: File, language: String): EffectDefinition? {
        if (JSONObject(File(folder, "$key.json").readText()).optBoolean("native")) return null
        // Each effect is parsed with its own one-entry index, so a bad file only drops itself.
        val parsed = EffectCatalog.parse(language) { name ->
            if (name == "index.json") "[${JSONObject.quote(key)}]".byteInputStream() else File(folder, name).inputStream()
        }.definitions.singleOrNull()?.takeIf { it.key == key } ?: return null
        // The full id is `<pluginId>.<key>`. The key is made unique too, so the effect can never pick up a built-in
        // effect's thumbnail, help page or translation, and it never counts as "new".
        val id = "$pluginId.$key"
        return parsed.copy(id = id, key = id, page = id, since = null, shortcuts = emptyList(), alsoIn = emptyList(), plugin = pluginId)
    }
}

/** This catalog with the effects of [plugins] added after the built-in ones, which stay first and unchanged. */
fun EffectCatalog.withPlugins(plugins: List<PluginStore.Installed>, language: String): EffectCatalog {
    val extra = plugins.flatMap { PluginEffects.read(it, language) }.distinctBy { it.id }.filter { added -> definitions.none { it.id == added.id } }
    return if (extra.isEmpty()) this else copy(definitions = definitions + extra)
}
