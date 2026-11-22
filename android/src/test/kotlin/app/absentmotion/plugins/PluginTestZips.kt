package app.absentmotion.plugins

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PluginTestZips {
    fun manifest(
        id: String = "kuep.test",
        version: String = "1.0",
        recipes: List<String> = emptyList(),
        effects: List<String> = emptyList(),
        scripts: List<String> = emptyList(),
    ): String {
        fun list(items: List<String>) = items.joinToString(",", "[", "]") { JSONObject.quote(it) }
        return """{"id":${JSONObject.quote(id)},"name":"Test","version":${JSONObject.quote(version)},"api":1,"permissions":[],""" +
            """"contributes":{"recipes":${list(recipes)},"effects":${list(effects)},"scripts":${list(scripts)}}}"""
    }

    fun zipOf(dir: File, vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("plugin", ".zip", dir)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    fun validZip(dir: File, id: String = "kuep.test", version: String = "1.0", recipeText: String = "{}"): File =
        zipOf(dir, "manifest.json" to manifest(id, version, recipes = listOf("recipes/a.json")), "recipes/a.json" to recipeText)
}
