package app.absentmotion.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestTest {
    private val valid = """{"id":"kuep.shakeintro","name":"Shake Intro","version":"1.0","api":1,"permissions":[],"contributes":{"recipes":["recipes/shake.json"],"effects":[],"scripts":[]}}"""

    private fun manifest(id: String = "kuep.shakeintro", api: String = "1", recipe: String = "recipes/shake.json") =
        """{"id":${quote(id)},"name":"Shake Intro","version":"1.0","api":$api,"permissions":[],"contributes":{"recipes":[${quote(recipe)}],"effects":[],"scripts":[]}}"""

    private fun quote(s: String) = org.json.JSONObject.quote(s)

    private fun invalid(json: String) = parseManifest(json) as? ManifestResult.Invalid

    @Test fun validManifestParses() {
        val result = parseManifest(valid) as ManifestResult.Valid
        assertEquals(
            PluginManifest(
                id = "kuep.shakeintro", name = "Shake Intro", version = "1.0", api = 1, permissions = emptyList(),
                recipes = listOf("recipes/shake.json"), effects = emptyList(), scripts = emptyList(),
            ),
            result.manifest,
        )
    }

    @Test fun idNeedsOwnerAndName() {
        for (id in listOf("shakeintro", "Kuep.Shake", "kuep.shake intro", "")) {
            assertTrue("'$id' should be invalid", invalid(manifest(id = id)) != null)
        }
        assertTrue(parseManifest(manifest(id = "kuep.shake.glow")) is ManifestResult.Valid)
    }

    @Test fun absentPrefixIsReserved() {
        val result = invalid(manifest(id = "absent.glow"))
        assertTrue(result != null)
        assertTrue(result!!.problems.any { "reserved" in it })
    }

    @Test fun api2IsValidAndApi3IsNot() {
        assertTrue(parseManifest(manifest(api = "2")) is ManifestResult.Valid)
        assertTrue(parseManifest(manifest(api = "1")) is ManifestResult.Valid)
        val result = invalid(manifest(api = "3"))
        assertTrue(result != null)
        assertTrue(result!!.problems.any { "api 3" in it })
    }

    private fun withScripts(api: String, vararg scripts: String) =
        """{"id":"kuep.scripted","name":"Scripted","version":"1.0","api":$api,"permissions":[],"contributes":{"recipes":[],"effects":[],"scripts":[${scripts.joinToString(",") { quote(it) }}]}}"""

    @Test fun api1WithScriptsIsInvalid() {
        val result = invalid(withScripts("1", "scripts/a.js"))
        assertTrue(result != null)
        assertTrue(result!!.problems.any { "scripts need api 2" in it })
    }

    @Test fun api2WithScriptsParses() {
        val result = parseManifest(withScripts("2", "scripts/a.js", "scripts/b.js")) as ManifestResult.Valid
        assertEquals(listOf("scripts/a.js", "scripts/b.js"), result.manifest.scripts)
        assertEquals(2, result.manifest.api)
    }

    @Test fun scriptPathsMustBeSafeAndEndInJs() {
        for (path in listOf("../x.js", "/x.js", "a.txt", "scripts/a.lua", "scripts\\a.js")) {
            assertTrue("'$path' should be invalid", invalid(withScripts("2", path)) != null)
        }
        assertTrue(invalid(withScripts("2", "a.txt"))!!.problems.any { "must end in .js" in it })
    }

    @Test fun apiMustBeAnIntegerOneOrTwo() {
        for (api in listOf("\"1\"", "1.5", "true", "0")) {
            val result = invalid(manifest(api = api))
            assertTrue("api $api should be invalid", result != null)
            assertTrue(result!!.problems.any { "api" in it })
        }
    }

    @Test fun contributedPathsStayInsidePackage() {
        for (path in listOf("../x.json", "/x.json", "a\\b.json", "a/../b.json", "C:\\x.json", "")) {
            assertTrue("'$path' should be invalid", invalid(manifest(recipe = path)) != null)
        }
        assertTrue(parseManifest(manifest(recipe = "recipes/ok.json")) is ManifestResult.Valid)
    }

    @Test fun badInputNeverThrows() {
        for (text in listOf("", "not json", "[]", "null", "{}")) {
            assertTrue("'$text' should be invalid", invalid(text) != null)
        }
        assertTrue(parseManifest("\uFEFF" + valid) is ManifestResult.Valid)
    }

    @Test fun everyProblemIsReportedNotJustTheFirst() {
        val result = invalid(manifest(id = "Bad", api = "9", recipe = "../x"))!!
        assertTrue(result.problems.size >= 3)
    }
}
