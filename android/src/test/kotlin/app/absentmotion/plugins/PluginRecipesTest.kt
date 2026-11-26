package app.absentmotion.plugins

import app.absentmotion.plugins.PluginTestZips.manifest
import app.absentmotion.plugins.PluginTestZips.zipOf
import app.absentmotion.plugins.script.QuickJsSandbox
import app.absentmotion.plugins.script.ScriptBudget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRecipesTest {
    @get:Rule val temp = TemporaryFolder()

    private val zipFolder by lazy { temp.newFolder("zips") }

    private fun store() = PluginStore(File(temp.root, "plugins"))

    private fun recipe(id: String) = """{"id":"$id","name":"Recipe $id","steps":[{"do":"addEffect","effect":"absent.glitch"}]}"""

    private fun install(store: PluginStore, id: String, recipes: Map<String, String>) {
        val paths = recipes.keys.map { "recipes/$it.json" }
        val entries = listOf("manifest.json" to manifest(id, recipes = paths)) + recipes.map { (name, text) -> "recipes/$name.json" to text }
        assertEquals(true, store.install(zipOf(zipFolder, *entries.toTypedArray())) is InstallResult.Done)
    }

    @Test fun listsRecipesOfEnabledPluginsOnly() {
        val store = store()
        install(store, "kuep.on", mapOf("a" to recipe("a")))
        install(store, "kuep.off", mapOf("b" to recipe("b")))
        store.setEnabled("kuep.off", false)
        assertEquals(listOf("a"), listRecipes(store).map { it.recipe.id })
    }

    @Test fun brokenRecipeFileIsSkippedNotFatal() {
        val store = store()
        install(store, "kuep.mixed", linkedMapOf("bad" to "{ nope", "good" to recipe("good"), "gone" to recipe("gone")))
        File(store.list().single().dir, "recipes/gone.json").delete()
        assertEquals(listOf("good"), listRecipes(store).map { it.recipe.id })
    }

    @Test fun recipesKeepPluginAndOrder() {
        val store = store()
        install(store, "kuep.zeta", linkedMapOf("second" to recipe("second"), "first" to recipe("first")))
        install(store, "kuep.alpha", mapOf("only" to recipe("only")))
        val found = listRecipes(store)
        assertEquals(listOf("only", "second", "first"), found.map { it.recipe.id })
        assertEquals(listOf("kuep.alpha", "kuep.zeta", "kuep.zeta"), found.map { it.plugin.id })
    }

    @Test fun noPluginsMeansNoRecipes() {
        assertEquals(emptyList<String>(), listRecipes(store()).map { it.recipe.id })
    }

    private val quick = QuickJsSandbox(ScriptBudget(millis = 800))

    private fun script(id: String) = "defineScript({ id: \"$id\", name: \"Script $id\", run: function (ctx) {} });"

    private fun installMixed(store: PluginStore, id: String, recipes: Map<String, String>, scripts: Map<String, String>) {
        val recipePaths = recipes.keys.map { "recipes/$it.json" }
        val scriptPaths = scripts.keys.map { "scripts/$it.js" }
        val entries = listOf("manifest.json" to manifest(id, recipes = recipePaths, scripts = scriptPaths)) +
            recipes.map { (name, text) -> "recipes/$name.json" to text } + scripts.map { (name, text) -> "scripts/$name.js" to text }
        assertEquals(true, store.install(zipOf(zipFolder, *entries.toTypedArray())) is InstallResult.Done)
    }

    @Test fun listsRecipesAndScriptsTogether() {
        val store = store()
        installMixed(store, "kuep.both", mapOf("a" to recipe("a")), mapOf("s" to script("s")))
        val tools = listTools(store, quick)
        assertEquals(listOf("Recipe a", "Script s"), tools.map { it.name })
        assertEquals(listOf(true, false), tools.map { it is PluginTool.OfRecipe })
        assertEquals(true, hasTools(store))
    }

    @Test fun brokenScriptIsSkippedNotFatal() {
        val store = store()
        installMixed(store, "kuep.mixed", mapOf("a" to recipe("a")), linkedMapOf("bad" to "this is not javascript (", "good" to script("good")))
        assertEquals(listOf("Recipe a", "Script good"), listTools(store, quick).map { it.name })
    }

    @Test fun disabledPluginHasNoTools() {
        val store = store()
        installMixed(store, "kuep.off", emptyMap(), mapOf("s" to script("s")))
        store.setEnabled("kuep.off", false)
        assertEquals(emptyList<String>(), listTools(store, quick).map { it.name })
        assertEquals(false, hasTools(store))
    }
}
