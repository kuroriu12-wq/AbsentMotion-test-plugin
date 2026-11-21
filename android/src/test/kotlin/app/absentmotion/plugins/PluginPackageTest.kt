package app.absentmotion.plugins

import app.absentmotion.plugins.PluginTestZips.manifest
import app.absentmotion.plugins.PluginTestZips.zipOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginPackageTest {
    @get:Rule val temp = TemporaryFolder()

    private val staging get() = File(temp.root, "staging")

    private fun stage(zip: File, limits: PackageLimits = PackageLimits()) = PluginPackage.stage(zip, staging, limits)

    private fun leftovers() = staging.listFiles().orEmpty().toList()

    @Test fun stagesAValidPackage() {
        val zip = zipOf(temp.root, "manifest.json" to manifest(recipes = listOf("recipes/a.json")), "recipes/a.json" to "{}")
        val result = stage(zip) as StageResult.Staged
        assertEquals("kuep.test", result.manifest.id)
        assertTrue(File(result.dir, "recipes/a.json").isFile)
        assertEquals(staging.canonicalFile, result.dir.canonicalFile.parentFile)
    }

    @Test fun rejectsTraversalEntries() {
        for (evil in listOf("../evil.txt", "a/../../evil.txt")) {
            val zip = zipOf(temp.root, "manifest.json" to manifest(), evil to "x")
            assertTrue("$evil should be rejected", stage(zip) is StageResult.Rejected)
        }
        assertTrue(temp.root.walkTopDown().none { it.name == "evil.txt" })
    }

    @Test fun rejectsAbsoluteAndBackslashEntries() {
        for (evil in listOf("/abs.txt", "C:\\x.txt", "a\\..\\b.txt")) {
            val zip = zipOf(temp.root, "manifest.json" to manifest(), evil to "x")
            assertTrue("$evil should be rejected", stage(zip) is StageResult.Rejected)
        }
        assertTrue(temp.root.walkTopDown().none { it.name == "abs.txt" || it.name == "b.txt" })
    }

    @Test fun rejectsMissingManifestAndMissingDeclaredFile() {
        val noManifest = zipOf(temp.root, "recipes/a.json" to "{}")
        assertTrue(stage(noManifest) is StageResult.Rejected)
        val noDeclared = zipOf(temp.root, "manifest.json" to manifest(recipes = listOf("recipes/a.json")))
        val result = stage(noDeclared) as StageResult.Rejected
        assertTrue(result.problems.any { "recipes/a.json" in it })
    }

    @Test fun rejectsNotAZipAndEmptyFile() {
        val text = File(temp.root, "not.zip").apply { writeText("this is not a zip") }
        val empty = File(temp.root, "empty.zip").apply { writeBytes(ByteArray(0)) }
        assertTrue(stage(text) is StageResult.Rejected)
        assertTrue(stage(empty) is StageResult.Rejected)
        assertTrue(stage(File(temp.root, "missing.zip")) is StageResult.Rejected)
    }

    @Test fun rejectsOverLimits() {
        val limits = PackageLimits(maxFiles = 2, maxBytes = 100)
        val tooMany = zipOf(temp.root, "manifest.json" to manifest(), "a.txt" to "1", "b.txt" to "2")
        assertTrue(stage(tooMany, limits) is StageResult.Rejected)
        val tooBig = zipOf(temp.root, "manifest.json" to manifest(), "big.txt" to "x".repeat(200))
        assertTrue(stage(tooBig, limits) is StageResult.Rejected)
        val fine = zipOf(temp.root, "manifest.json" to manifest())
        assertTrue(stage(fine, PackageLimits(maxFiles = 2, maxBytes = 1000)) is StageResult.Staged)
    }

    @Test fun rejectedLeavesNoStagingFolder() {
        stage(zipOf(temp.root, "manifest.json" to manifest(), "../evil.txt" to "x"))
        stage(zipOf(temp.root, "recipes/a.json" to "{}"))
        stage(File(temp.root, "nothing.zip"))
        assertEquals(emptyList<File>(), leftovers())
    }
}
