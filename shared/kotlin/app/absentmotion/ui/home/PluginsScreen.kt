package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.platform.ActivityResultContracts
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.rememberLauncherForActivityResult
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.PluginsModel
import app.absentmotion.plugins.pluginHelp
import app.absentmotion.ui.editor.panels.ThinSwitch
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val MaxZipBytes = 64L * 1024 * 1024

@Composable
internal fun PluginsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val model = remember { PluginsModel(PluginStore(context)) }
    PluginsScreen(model, rememberZipPicker(), onBack)
}

@Composable
private fun rememberZipPicker(): ((File) -> Unit) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<((File) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val deliver = waiting
        waiting = null
        if (uri != null && deliver != null) scope.launch {
            withContext(Dispatchers.IO) {
                val target = File(context.cacheDir, "plugin-${UUID.randomUUID()}.zip")
                try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) target.delete() else input.use { source -> target.outputStream().use { sink -> copyAtMost(source, sink, MaxZipBytes) } }
                } catch (e: java.io.IOException) {
                    target.delete()
                }
                deliver(target)
            }
        }
    }
    return { deliver ->
        waiting = deliver
        picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }
}

private fun copyAtMost(source: java.io.InputStream, sink: java.io.OutputStream, limit: Long) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw java.io.IOException("too large")
        sink.write(buffer, 0, read)
    }
}

@Composable
internal fun PluginsScreen(model: PluginsModel, pickZip: ((File) -> Unit) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lastZip by remember { mutableStateOf<File?>(null) }
    fun forget() {
        lastZip?.delete()
        lastZip = null
    }
    DisposableEffect(Unit) { onDispose { forget() } }
    ToolSheet(stringResource(R.string.tools_plugins), onBack) {
        Spacer(Modifier.height(20.dp))
        BigButton(stringResource(R.string.plugins_install), Modifier.fillMaxWidth(), HomeIcons.Plus) {
            pickZip { file ->
                forget()
                lastZip = file
                model.install(file)
                if (model.pendingReplace == null) forget()
            }
        }
        if (model.restartNeeded) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.plugins_restart), color = Am.Accent, fontSize = 14.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (model.items.isEmpty()) Text(stringResource(R.string.plugins_empty), color = Am.HomeDim, fontSize = 15.sp, lineHeight = 20.sp)
        for (plugin in model.items) PluginRow(plugin, { model.toggle(plugin.id) }, { model.remove(plugin.id) })
        Spacer(Modifier.height(24.dp))
    }
    model.pendingReplace?.let { pending ->
        AlertDialog(
            onDismissRequest = { model.cancelReplace(); forget() },
            title = { Text(stringResource(R.string.plugins_replace_title), color = Am.Navy) },
            text = { Text(stringResource(R.string.plugins_replace_body, pending.incoming.name, pending.existing.version, pending.incoming.version), color = Am.Navy) },
            confirmButton = {
                TextButton({
                    val zip = lastZip
                    if (zip != null && zip.isFile) {
                        scope.launch(Dispatchers.IO) {
                            model.confirmReplace(zip)
                            forget()
                        }
                    } else {
                        model.cancelReplace()
                        forget()
                    }
                }) { Text(stringResource(R.string.plugins_replace), color = Am.Accent) }
            },
            dismissButton = { TextButton({ model.cancelReplace(); forget() }) { Text(stringResource(R.string.cancel), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
    model.message?.let { text ->
        AlertDialog(
            onDismissRequest = { model.dismissMessage() },
            title = { Text(stringResource(R.string.plugins_failed), color = Am.Navy) },
            text = { Text(text, color = Am.Navy) },
            confirmButton = { TextButton({ model.dismissMessage() }) { Text(stringResource(R.string.ok), color = Am.Navy) } },
            containerColor = Am.Card,
        )
    }
}

@Composable
private fun PluginRow(plugin: PluginStore.Installed, onToggle: () -> Unit, onRemove: () -> Unit) {
    val manifest = plugin.manifest
    var helpOpen by remember { mutableStateOf(false) }
    val help by produceState<String?>(null, helpOpen) { value = if (helpOpen) withContext(Dispatchers.IO) { pluginHelp(plugin) } else null }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(Am.Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(manifest?.name ?: plugin.id, color = Am.Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(plugin.id, color = Am.HomeDim, fontSize = 13.sp)
                if (manifest != null) Text(stringResource(R.string.plugins_version, manifest.version), color = Am.HomeDim, fontSize = 13.sp)
            }
            ThinSwitch(on = plugin.enabled && plugin.problem == null, enabled = plugin.problem == null) { onToggle() }
        }
        if (manifest != null && manifest.description.isNotEmpty()) Text(manifest.description, Modifier.padding(top = 6.dp), color = Am.Navy, fontSize = 14.sp, lineHeight = 18.sp)
        if (manifest != null && manifest.author.isNotEmpty()) Text(stringResource(R.string.plugin_author, manifest.author), Modifier.padding(top = 4.dp), color = Am.HomeDim, fontSize = 13.sp)
        if (plugin.problem != null) Text(plugin.problem, Modifier.padding(top = 6.dp), color = Am.Accent, fontSize = 14.sp, lineHeight = 18.sp)
        if (manifest != null && manifest.permissions.isNotEmpty()) {
            Text(
                stringResource(R.string.plugins_asks, manifest.permissions.joinToString(", ")) + " · " + stringResource(R.string.plugins_not_granted),
                Modifier.padding(top = 6.dp), color = Am.HomeDim, fontSize = 13.sp, lineHeight = 17.sp,
            )
        }
        if (helpOpen) Text(help ?: stringResource(R.string.plugin_help_none), Modifier.padding(top = 8.dp), color = Am.Navy, fontSize = 14.sp, lineHeight = 19.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (manifest != null) TextButton({ helpOpen = !helpOpen }) { Text(stringResource(if (helpOpen) R.string.plugin_help_hide else R.string.plugin_help), color = Am.Navy) }
            TextButton(onRemove) { Text(stringResource(R.string.delete), color = Am.Navy) }
        }
    }
}
