package app.absentmotion.plugins.script

import app.absentmotion.plugins.InstallResult
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.PluginTestZips.manifest
import app.absentmotion.plugins.PluginTestZips.zipOf
import app.absentmotion.plugins.RecipeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginScriptTest {
    @get:Rule val temp = TemporaryFolder()

    private val store by lazy { PluginStore(File(temp.root, "plugins")) }

    private val zipFolder by lazy { temp.newFolder("zips") }

    private val quick = QuickJsSandbox(ScriptBudget(millis = 800))

    private fun install(id: String = "kuep.test", vararg scripts: Pair<String, String>) {
        val paths = scripts.map { it.first }
        val zip = zipOf(zipFolder, "manifest.json" to manifest(id = id, scripts = paths), *scripts)
        assertTrue(store.install(zip) is InstallResult.Done)
    }

    private fun problemOf(script: String): String {
        install(scripts = arrayOf("scripts/a.js" to script))
        val listing = loadScripts(store, quick)
        assertTrue("expected no scripts, got ${listing.scripts.map { it.id }}", listing.scripts.isEmpty())
        return listing.problems.getValue("kuep.test/scripts/a.js")
    }

    @Test fun validScriptGivesItsIdNameAndTypedInputs() {
        install(
            scripts = arrayOf(
                "scripts/physics.js" to """
                    defineScript({
                        id: "physics",
                        name: "Physics",
                        inputs: [
                            { id: "gravity", label: "Gravity", type: "number", default: 3, min: 0, max: 40 },
                            { id: "mode", label: "Mode", type: "choice", default: "soft", options: ["soft", "hard"] },
                            { id: "walls", label: "Walls", type: "switch", default: true },
                        ],
                        run(ctx) {},
                    });
                """.trimIndent(),
            ),
        )
        val script = listScripts(store, quick).single()
        assertEquals("physics", script.id)
        assertEquals("Physics", script.name)
        assertEquals("scripts/physics.js", script.path)
        assertEquals("kuep.test", script.plugin.id)
        assertEquals(
            listOf(
                RecipeInput("gravity", "Gravity", "number", "3", emptyList(), 0f, 40f),
                RecipeInput("mode", "Mode", "choice", "soft", listOf("soft", "hard")),
                RecipeInput("walls", "Walls", "switch", "true"),
            ),
            script.inputs,
        )
        assertTrue(script.source.contains("defineScript"))
        assertTrue(scriptProblems(store, quick).isEmpty())
    }

    @Test fun scriptWithoutInputsHasAnEmptyForm() {
        install(scripts = arrayOf("scripts/a.js" to "defineScript({ id: 'a', name: 'A', run() {} });"))
        assertEquals(emptyList<RecipeInput>(), listScripts(store, quick).single().inputs)
    }

    @Test fun missingRunIsSkippedWithAReadableProblem() {
        val problem = problemOf("defineScript({ id: 'a', name: 'A' });")
        assertTrue(problem, problem.contains("run function"))
    }

    @Test fun badInputTypeIsSkipped() {
        val problem = problemOf("defineScript({ id: 'a', name: 'A', inputs: [{ id: 'x', label: 'X', type: 'slider', default: 1 }], run() {} });")
        assertTrue(problem, problem.contains("type must be one of"))
    }

    @Test fun duplicateInputIdIsSkipped() {
        val problem = problemOf(
            "defineScript({ id: 'a', name: 'A', inputs: [" +
                "{ id: 'x', label: 'X', type: 'number', default: 1 }, { id: 'x', label: 'Y', type: 'number', default: 2 }], run() {} });",
        )
        assertTrue(problem, problem.contains("used twice"))
    }

    @Test fun inputsGoThroughTheRecipeChecks() {
        val problem = problemOf(
            "defineScript({ id: 'a', name: 'A', inputs: [{ id: 'm', label: 'M', type: 'choice', default: 'z', options: ['a', 'b'] }], run() {} });",
        )
        assertTrue(problem, problem.contains("default must be one of the options"))
    }

    @Test fun twoDefineScriptCallsAreSkipped() {
        val problem = problemOf("defineScript({ id: 'a', name: 'A', run() {} }); defineScript({ id: 'b', name: 'B', run() {} });")
        assertTrue(problem, problem.contains("more than once"))
    }

    @Test fun noDefineScriptCallIsSkipped() {
        val problem = problemOf("var x = 1;")
        assertTrue(problem, problem.contains("never calls defineScript"))
    }

    @Test fun badIdAndMissingNameAreSkipped() {
        val problem = problemOf("defineScript({ id: 'has space', run() {} });")
        assertTrue(problem, problem.contains("id must start with a letter"))
        assertTrue(problem, problem.contains("name is required"))
    }

    @Test fun scriptThatThrowsWhileLoadingIsSkippedWithItsLine() {
        val problem = problemOf("var a = 1;\nthrow new Error('broken');")
        assertTrue(problem, problem.contains("broken"))
        assertTrue(problem, problem.contains("line 2"))
    }

    @Test fun syntaxErrorIsSkipped() {
        val problem = problemOf("defineScript({ id: ")
        assertTrue(problem.isNotBlank())
    }

    @Test fun ctxDoesNotExistAtLoadTime() {
        val problem = problemOf("ctx.log('x'); defineScript({ id: 'a', name: 'A', run() {} });")
        assertTrue(problem, problem.contains("ctx"))
    }

    @Test fun scriptThatLoopsForeverIsSkippedWithinTheLimit() {
        install(scripts = arrayOf("scripts/a.js" to "while (true) {}"))
        val start = System.nanoTime()
        val listing = loadScripts(store, quick)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(listing.scripts.isEmpty())
        assertTrue(listing.problems.getValue("kuep.test/scripts/a.js"), listing.problems.getValue("kuep.test/scripts/a.js").contains("longer than"))
        assertTrue("took $ms ms", ms < 4000)
    }

    @Test fun oneBrokenScriptDoesNotHideTheOthers() {
        install(
            scripts = arrayOf(
                "scripts/bad.js" to "throw new Error('no');",
                "scripts/good.js" to "defineScript({ id: 'good', name: 'Good', run() {} });",
            ),
        )
        val listing = loadScripts(store, quick)
        assertEquals(listOf("good"), listing.scripts.map { it.id })
        assertEquals(listOf("kuep.test/scripts/bad.js"), listing.problems.keys.toList())
    }

    @Test fun twoScriptsWithTheSameIdKeepTheFirst() {
        install(
            scripts = arrayOf(
                "scripts/a.js" to "defineScript({ id: 'same', name: 'First', run() {} });",
                "scripts/b.js" to "defineScript({ id: 'same', name: 'Second', run() {} });",
            ),
        )
        val listing = loadScripts(store, quick)
        assertEquals(listOf("First"), listing.scripts.map { it.name })
        assertTrue(listing.problems.getValue("kuep.test/scripts/b.js").contains("already uses the id"))
    }

    @Test fun disabledPluginsAreNotLoaded() {
        install(scripts = arrayOf("scripts/a.js" to "defineScript({ id: 'a', name: 'A', run() {} });"))
        store.setEnabled("kuep.test", false)
        val listing = loadScripts(store, quick)
        assertTrue(listing.scripts.isEmpty())
        assertTrue(listing.problems.isEmpty())
    }

    @Test fun scriptsOfDifferentPluginsAreBothListed() {
        install("kuep.one", "scripts/a.js" to "defineScript({ id: 'a', name: 'A', run() {} });")
        install("kuep.two", "scripts/a.js" to "defineScript({ id: 'a', name: 'A', run() {} });")
        assertEquals(setOf("kuep.one", "kuep.two"), listScripts(store, quick).map { it.plugin.id }.toSet())
    }

    @Test fun oversizedScriptIsSkippedWithoutRunning() {
        val big = "defineScript({ id: 'a', name: 'A', run() {} });\n" + "//".padEnd(600 * 1024, 'x')
        install(scripts = arrayOf("scripts/a.js" to big))
        val problem = loadScripts(store, quick).problems.getValue("kuep.test/scripts/a.js")
        assertTrue(problem, problem.contains("larger than"))
    }
}
