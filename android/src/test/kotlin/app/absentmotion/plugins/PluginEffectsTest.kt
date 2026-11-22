package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginEffectsTest {
    @get:Rule val temp = TemporaryFolder()

    private val pack = File("../shared/assets/effects")
    private val builtIn = EffectCatalog.parse { File(pack, it).inputStream() }

    private fun effectJson(key: String = "pulse", change: (JSONObject) -> Unit = {}): String =
        JSONObject(File(pack, "sepia.json").readText()).also { it.put("id", key); change(it) }.toString()

    private fun plugin(
        id: String = "kuep.pulseglow",
        files: Map<String, String> = mapOf("pulse" to effectJson()),
        enabled: Boolean = true,
        problem: String? = null,
    ): PluginStore.Installed {
        val dir = File(temp.root, id)
        for ((key, text) in files) File(dir, "effects/$key.json").apply { parentFile.mkdirs() }.writeText(text)
        val manifest = PluginManifest(id, "Pulse", "1.0", 1, emptyList(), emptyList(), files.keys.toList(), emptyList())
        return PluginStore.Installed(manifest, id, dir, enabled, problem)
    }

    @Test fun pluginEffectGetsPrefixedIdAndProvenance() {
        val effect = PluginEffects.read(plugin(), "en").single()
        assertEquals("kuep.pulseglow.pulse", effect.id)
        assertEquals("kuep.pulseglow", effect.plugin)
        assertEquals(builtIn.find("absent.sepia")!!.params.map { it.id }, effect.params.map { it.id })
        assertTrue(effect.sksl != null && !effect.native)
    }

    @Test fun aPluginEffectNeverBorrowsABuiltInKeyOrNewBadge() {
        val effect = PluginEffects.read(plugin(files = mapOf("sepia" to effectJson("sepia") { it.put("since", "9.9.9") })), "en").single()
        assertEquals("kuep.pulseglow.sepia", effect.id)
        assertTrue(effect.key != "sepia")
        assertEquals(null, effect.since)
    }

    @Test fun nativeFlagIsRejected() {
        assertEquals(emptyList<String>(), PluginEffects.read(plugin(files = mapOf("pulse" to effectJson { it.put("native", true) })), "en").map { it.id })
    }

    @Test fun unknownCategoryIsRejected() {
        assertEquals(emptyList<String>(), PluginEffects.read(plugin(files = mapOf("pulse" to effectJson { it.put("category", "nonsense") })), "en").map { it.id })
    }

    @Test fun anEffectWithoutAShaderIsRejected() {
        assertEquals(emptyList<String>(), PluginEffects.read(plugin(files = mapOf("pulse" to effectJson { it.put("sksl", "") })), "en").map { it.id })
    }

    @Test fun aFileWhoseIdDoesNotMatchItsKeyIsRejected() {
        assertEquals(emptyList<String>(), PluginEffects.read(plugin(files = mapOf("pulse" to effectJson("other"))), "en").map { it.id })
    }

    @Test fun oneBrokenEffectDoesNotHideTheOthers() {
        val files = linkedMapOf("bad" to "{ not json", "pulse" to effectJson(), "missing" to effectJson("missing"))
        val made = plugin(files = files)
        File(made.dir, "effects/missing.json").delete()
        assertEquals(listOf("kuep.pulseglow.pulse"), PluginEffects.read(made, "en").map { it.id })
    }

    @Test fun disabledOrBrokenPluginContributesNothing() {
        assertEquals(emptyList<String>(), PluginEffects.read(plugin(enabled = false), "en").map { it.id })
        assertEquals(emptyList<String>(), PluginEffects.read(plugin(problem = "broken"), "en").map { it.id })
        assertEquals(emptyList<String>(), PluginEffects.read(plugin().copy(manifest = null), "en").map { it.id })
    }

    @Test fun mergedCatalogKeepsBuiltInsFirstAndUnchanged() {
        val merged = builtIn.withPlugins(listOf(plugin()), "en")
        assertEquals(builtIn.definitions, merged.definitions.take(builtIn.definitions.size))
        assertEquals(listOf("kuep.pulseglow.pulse"), merged.definitions.drop(builtIn.definitions.size).map { it.id })
        assertEquals("kuep.pulseglow.pulse", merged.find("kuep.pulseglow.pulse")?.id)
    }

    @Test fun twoPluginsCanShipTheSameKey() {
        val merged = builtIn.withPlugins(listOf(plugin("kuep.one"), plugin("kuep.two")), "en")
        assertEquals(listOf("kuep.one.pulse", "kuep.two.pulse"), merged.definitions.drop(builtIn.definitions.size).map { it.id })
    }

    @Test fun noPluginsMeansTheSameCatalog() {
        assertEquals(builtIn, builtIn.withPlugins(emptyList(), "en"))
    }
}
