package app.absentmotion.plugins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class PluginsModel(private val store: PluginStore) {
    var items by mutableStateOf(readItems())
        private set
    var pendingReplace by mutableStateOf<InstallResult.NeedsConfirm?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var restartNeeded by mutableStateOf(false)
        private set

    fun install(zip: File) {
        message = null
        pendingReplace = null
        run(zip, replace = false)
    }

    fun confirmReplace(zip: File) {
        val replacing = pendingReplace
        pendingReplace = null
        message = null
        run(zip, replace = true)
        if (replacing != null && (replacing.existing.effects.isNotEmpty() || replacing.incoming.effects.isNotEmpty())) restartNeeded = true
    }

    fun cancelReplace() {
        pendingReplace = null
    }

    fun toggle(id: String) {
        val plugin = items.firstOrNull { it.id == id } ?: return
        attempt {
            store.setEnabled(id, !plugin.enabled)
            if (plugin.manifest?.effects?.isNotEmpty() == true) restartNeeded = true
        }
        items = readItems()
    }

    fun remove(id: String) {
        val plugin = items.firstOrNull { it.id == id }
        attempt {
            store.remove(id)
            if (plugin?.manifest?.effects?.isNotEmpty() == true) restartNeeded = true
        }
        items = readItems()
    }

    private fun run(zip: File, replace: Boolean) {
        attempt {
            when (val result = store.install(zip, replace)) {
                is InstallResult.Done -> if (result.plugin.manifest?.effects?.isNotEmpty() == true) restartNeeded = true
                is InstallResult.NeedsConfirm -> pendingReplace = result
                is InstallResult.Rejected -> message = result.problems.joinToString("\n")
            }
        }
        items = readItems()
    }

    private fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            message = e.message ?: e.javaClass.simpleName
        }
    }

    private fun readItems(): List<PluginStore.Installed> = try {
        store.list()
    } catch (e: Exception) {
        emptyList()
    }
}
