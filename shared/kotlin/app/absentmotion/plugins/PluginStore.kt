package app.absentmotion.plugins

import android.content.Context
import app.absentmotion.library.writeAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed interface InstallResult {
    data class Done(val plugin: PluginStore.Installed) : InstallResult
    data class NeedsConfirm(val existing: PluginManifest, val incoming: PluginManifest) : InstallResult
    data class Rejected(val problems: List<String>) : InstallResult
}

/**
 * Installed plugins, one folder each under [root]. `state.json` lists the disabled ids, `.staging` holds a package while it
 * is checked and `.old` holds the previous version while a plugin is replaced. A plugin that cannot be read is listed with
 * a problem; it never throws and never blocks the others.
 */
class PluginStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "plugins"))

    data class Installed(
        val manifest: PluginManifest?,
        val id: String,
        val dir: File,
        val enabled: Boolean,
        val problem: String?,
    )

    private val staging = File(root, ".staging")
    private val old = File(root, ".old")
    private val stateFile = File(root, "state.json")

    init {
        synchronized(lock) { recover() }
    }

    fun list(): List<Installed> = synchronized(lock) { listLocked() }

    private fun listLocked(): List<Installed> {
        val disabled = disabledIds()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .map { describe(it, disabled) }
    }

    /** The plugins that are switched on and load without a problem. */
    fun enabled(): List<Installed> = list().filter { it.enabled && it.problem == null }

    fun install(zip: File, replace: Boolean = false): InstallResult = synchronized(lock) { installLocked(zip, replace) }

    private fun installLocked(zip: File, replace: Boolean): InstallResult {
        val staged = when (val result = PluginPackage.stage(zip, staging)) {
            is StageResult.Rejected -> return InstallResult.Rejected(result.problems)
            is StageResult.Staged -> result
        }
        val incoming = staged.manifest
        if (incoming.scripts.isNotEmpty()) {
            staged.dir.deleteRecursively()
            return InstallResult.Rejected(listOf("scripts are not supported by this version of AbsentMotion yet"))
        }
        val target = File(root, incoming.id)
        if (target.exists()) {
            // A plugin whose files are already broken is replaced without asking: there is nothing to keep.
            val existing = describe(target, emptySet()).manifest
            if (existing != null && !replace) {
                staged.dir.deleteRecursively()
                return InstallResult.NeedsConfirm(existing, incoming)
            }
            if (!swapIn(staged.dir, target)) {
                staged.dir.deleteRecursively()
                return InstallResult.Rejected(listOf("could not replace '${incoming.id}'; the old version is unchanged"))
            }
        } else {
            if (!staged.dir.renameTo(target)) {
                staged.dir.deleteRecursively()
                return InstallResult.Rejected(listOf("could not install '${incoming.id}'"))
            }
            val disabled = disabledIds()
            if (incoming.id in disabled) runCatching { saveDisabled(disabled - incoming.id) }
        }
        return InstallResult.Done(describe(target, disabledIds()))
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) { setEnabledLocked(id, enabled) }

    private fun setEnabledLocked(id: String, enabled: Boolean) {
        if (!isValidPluginId(id)) return
        val disabled = disabledIds()
        saveDisabled(if (enabled) disabled - id else disabled + id)
    }

    fun remove(id: String) = synchronized(lock) { removeLocked(id) }

    private fun removeLocked(id: String) {
        if (!isValidPluginId(id)) return
        File(root, id).deleteRecursively()
        val disabled = disabledIds()
        if (id in disabled) saveDisabled(disabled - id)
    }

    private fun describe(dir: File, disabled: Set<String>): Installed {
        val id = dir.name
        val file = File(dir, "manifest.json")
        val result = if (file.isFile) {
            runCatching { parseManifest(file.readText()) }
                .getOrElse { ManifestResult.Invalid(listOf("manifest.json cannot be read: ${it.message}")) }
        } else {
            ManifestResult.Invalid(listOf("manifest.json is missing"))
        }
        val manifest = (result as? ManifestResult.Valid)?.manifest
        val problem = when {
            result is ManifestResult.Invalid -> result.problems.joinToString("; ")
            manifest != null && manifest.id != id -> "the manifest id '${manifest.id}' does not match its folder '$id'"
            else -> null
        }
        return Installed(manifest.takeIf { problem == null }, id, dir, id !in disabled, problem)
    }

    /** Moves [fresh] into place of [target], keeping the old folder aside so a failure can put it back. */
    private fun swapIn(fresh: File, target: File): Boolean {
        old.mkdirs()
        val backup = File(old, target.name)
        backup.deleteRecursively()
        if (!target.renameTo(backup)) return false
        if (!fresh.renameTo(target)) {
            backup.renameTo(target)
            return false
        }
        backup.deleteRecursively()
        return true
    }

    /** Cleans up after a crash: drops half-staged packages and puts back a plugin whose replacement did not finish. */
    private fun recover() {
        staging.deleteRecursively()
        for (leftover in old.listFiles().orEmpty()) {
            val target = File(root, leftover.name)
            if (isValidPluginId(leftover.name) && !target.exists()) leftover.renameTo(target) else leftover.deleteRecursively()
        }
        old.deleteRecursively()
    }

    private fun disabledIds(): Set<String> = try {
        val array = JSONObject(stateFile.readText()).optJSONArray("disabled")
        buildSet {
            if (array != null) for (i in 0 until array.length()) (array.opt(i) as? String)?.let { add(it) }
        }
    } catch (e: Exception) {
        emptySet()
    }

    private fun saveDisabled(disabled: Set<String>) {
        val array = JSONArray()
        for (id in disabled.sorted()) array.put(id)
        writeAtomically(stateFile, JSONObject().put("disabled", array).toString())
    }

    private companion object {
        /** One lock for every store on the same folder, so a catalog read can never clean up an install that is still running. */
        val lock = Any()
    }
}
