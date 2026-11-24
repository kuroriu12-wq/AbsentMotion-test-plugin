package app.absentmotion.plugins

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val api: Int,
    val permissions: List<String>,
    val recipes: List<String>,
    val effects: List<String>,
    val scripts: List<String>,
) {
    companion object {
        const val HOST_API = 1
    }
}

sealed interface ManifestResult {
    data class Valid(val manifest: PluginManifest) : ManifestResult
    data class Invalid(val problems: List<String>) : ManifestResult
}

private val IdPattern = Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+")
private val EffectKeyPattern = Regex("[A-Za-z0-9_]+")
private val DrivePrefix = Regex("^[A-Za-z]:")

internal fun isValidPluginId(id: String): Boolean = IdPattern.matches(id)

internal fun isSafePackagePath(path: String): Boolean =
    path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && !DrivePrefix.containsMatchIn(path) &&
        path.split('/').none { it == ".." }

fun parseManifest(json: String): ManifestResult {
    val root = try {
        JSONObject(json.removePrefix("﻿\uFEFF"))
    } catch (e: JSONException) {
        return ManifestResult.Invalid(listOf("manifest.json is not a valid JSON object: ${e.message}"))
    }
    val problems = ArrayList<String>()

    val id = root.opt("id") as? String
    if (id == null || !IdPattern.matches(id)) {
        problems += "id must be 'owner.name' using lowercase letters, digits and underscores"
    } else if (id.startsWith("absent.")) {
        problems += "id prefix 'absent.' is reserved for built-in effects"
    }

    val name = (root.opt("name") as? String)?.takeIf { it.isNotBlank() }
    if (name == null) problems += "name is required"
    val version = (root.opt("version") as? String)?.takeIf { it.isNotBlank() }
    if (version == null) problems += "version is required"

    val api = root.opt("api")
    if (api !is Int) {
        problems += "api must be the integer ${PluginManifest.HOST_API}"
    } else if (api != PluginManifest.HOST_API) {
        problems += "api $api is not supported; this app supports api ${PluginManifest.HOST_API}"
    }

    fun strings(owner: JSONObject, key: String, label: String, required: Boolean): List<String> {
        if (!owner.has(key)) {
            if (required) problems += "$label is required"
            return emptyList()
        }
        val array = owner.opt(key) as? JSONArray
        if (array == null) {
            problems += "$label must be a list"
            return emptyList()
        }
        val out = ArrayList<String>()
        for (i in 0 until array.length()) {
            val item = array.opt(i) as? String
            if (item == null) problems += "$label[$i] must be a string" else out += item
        }
        return out
    }

    val permissions = strings(root, "permissions", "permissions", required = true)
    val contributes = root.opt("contributes") as? JSONObject
    var recipes = emptyList<String>()
    var effects = emptyList<String>()
    var scripts = emptyList<String>()
    if (contributes == null) {
        problems += "contributes is required and must be an object"
    } else {
        recipes = strings(contributes, "recipes", "contributes.recipes", required = false)
        effects = strings(contributes, "effects", "contributes.effects", required = false)
        scripts = strings(contributes, "scripts", "contributes.scripts", required = false)
        for (path in recipes) if (!isSafePackagePath(path)) problems += "recipe path '$path' must stay inside the package"
        for (path in scripts) if (!isSafePackagePath(path)) problems += "script path '$path' must stay inside the package"
        for (key in effects) if (!EffectKeyPattern.matches(key)) problems += "effect key '$key' may only use letters, digits and underscores"
    }

    if (problems.isNotEmpty() || id == null || name == null || version == null || api !is Int) {
        return ManifestResult.Invalid(problems)
    }
    return ManifestResult.Valid(PluginManifest(id, name, version, api, permissions, recipes, effects, scripts))
}
