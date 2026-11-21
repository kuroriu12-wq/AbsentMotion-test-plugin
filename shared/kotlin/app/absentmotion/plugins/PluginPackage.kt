package app.absentmotion.plugins

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** Caps on what one plugin package may unpack to. Size is counted while extracting, never taken from the zip header. */
data class PackageLimits(val maxFiles: Int = 500, val maxBytes: Long = 20L * 1024 * 1024)

sealed interface StageResult {
    data class Staged(val dir: File, val manifest: PluginManifest) : StageResult
    data class Rejected(val problems: List<String>) : StageResult
}

private class PackageRejected(val problems: List<String>) : Exception(problems.joinToString("; "))

object PluginPackage {
    /**
     * Unpacks [zip] into a fresh folder under [stagingRoot] and checks it. On [StageResult.Rejected] no folder is left
     * behind. Nothing is ever written outside the fresh folder.
     */
    fun stage(zip: File, stagingRoot: File, limits: PackageLimits = PackageLimits()): StageResult {
        val dir = try {
            stagingRoot.mkdirs()
            Files.createTempDirectory(stagingRoot.toPath(), "stage-").toFile()
        } catch (e: IOException) {
            return StageResult.Rejected(listOf("could not create a staging folder: ${e.message}"))
        }
        return try {
            extract(zip, dir, limits)
            StageResult.Staged(dir, check(dir))
        } catch (e: PackageRejected) {
            dir.deleteRecursively()
            StageResult.Rejected(e.problems)
        } catch (e: Exception) {
            dir.deleteRecursively()
            StageResult.Rejected(listOf("could not read the package: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun extract(zip: File, dir: File, limits: PackageLimits) {
        val root = dir.canonicalFile
        val seen = HashSet<String>()
        var files = 0
        var bytes = 0L
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                val name = entry.name
                val bare = name.trimEnd('/')
                if (!isSafePackagePath(bare)) throw PackageRejected(listOf("unsafe path in package: '$name'"))
                val target = File(root, bare).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw PackageRejected(listOf("unsafe path in package: '$name'"))
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (!seen.add(bare)) throw PackageRejected(listOf("the package lists '$name' twice"))
                files++
                if (files > limits.maxFiles) throw PackageRejected(listOf("the package has more than ${limits.maxFiles} files"))
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        if (bytes > limits.maxBytes) throw PackageRejected(listOf("the package unpacks to more than ${limits.maxBytes} bytes"))
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (entries == 0) throw PackageRejected(listOf("this is not a zip package, or it is empty"))
    }

    private fun check(dir: File): PluginManifest {
        val file = File(dir, "manifest.json")
        if (!file.isFile) throw PackageRejected(listOf("manifest.json is missing from the package"))
        val manifest = when (val parsed = parseManifest(file.readText())) {
            is ManifestResult.Invalid -> throw PackageRejected(parsed.problems)
            is ManifestResult.Valid -> parsed.manifest
        }
        val declared = manifest.recipes + manifest.effects.map { "effects/$it.json" }
        val missing = declared.filter { !File(dir, it).isFile }
        if (missing.isNotEmpty()) throw PackageRejected(missing.map { "declared file '$it' is missing from the package" })
        return manifest
    }
}
