package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.platform.LocalContext
import app.absentmotion.editor.MultiSelect
import app.absentmotion.editor.commitScript
import app.absentmotion.editor.scriptSelection
import app.absentmotion.plugins.PluginTool
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.RecipeInput
import app.absentmotion.plugins.RecipeRunner
import app.absentmotion.plugins.RunResult
import app.absentmotion.plugins.listTools
import app.absentmotion.plugins.script.PluginScript
import app.absentmotion.plugins.script.QuickJsSandbox
import app.absentmotion.plugins.script.ScriptResult
import app.absentmotion.plugins.script.ScriptRunner
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.withUiFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private class RunFailure(val reason: String, val script: Boolean, val step: Int? = null, val line: Int? = null, val changed: Boolean = false)

@Composable
internal fun PluginRecipeDialog(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val several = MultiSelect.active
    val tools by produceState<List<PluginTool>?>(null) { value = withContext(Dispatchers.Default) { listTools(PluginStore(context)).filter { !several || it is PluginTool.OfScript } } }
    val catalog by state.engine.effects.collectAsState()
    val scope = rememberCoroutineScope()
    val stop = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) { onDispose { stop.set(true) } }
    var chosen by remember { mutableStateOf<PluginTool?>(null) }
    var failure by remember { mutableStateOf<RunFailure?>(null) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    val values = remember(chosen) { mutableStateMapOf<String, String>().also { map -> chosen?.inputs?.forEach { map[it.id] = it.default } } }
    val picked = chosen
    fun runRecipe(recipe: PluginTool.OfRecipe) {
        val layer = state.selected ?: return
        state.playing = false
        when (val result = RecipeRunner(catalog).run(state.scene, layer.id, recipe.entry.recipe, values.toMap(), state.sampleMs)) {
            is RunResult.Done -> {
                state.edit { result.scene }
                onClose()
            }
            is RunResult.Failed -> failure = RunFailure(result.reason, false, step = result.stepIndex)
        }
    }
    fun runScript(script: PluginScript) {
        val ids = state.scriptSelection()
        val start = state.scene
        val sampleMs = state.sampleMs
        val given = values.toMap()
        state.playing = false
        failure = null
        log = emptyList()
        stop.set(false)
        running = true
        scope.launch {
            val boxes = HashMap<Long, FloatArray>()
            for (id in ids) {
                val layer = start.layer(id) ?: continue
                state.engine.layerBounds(id, layer.startTime)?.let { boxes[id] = it }
            }
            val result = withContext(Dispatchers.Default) {
                ScriptRunner(catalog) { QuickJsSandbox() }.run(start, ids, script, given, sampleMs, boxes) { stop.get() }
            }
            running = false
            when (result) {
                is ScriptResult.Done -> when {
                    !state.commitScript(start, result.scene) -> failure = RunFailure("", true, changed = true)
                    result.log.isEmpty() -> onClose()
                    else -> {
                        log = result.log
                        finished = true
                    }
                }
                is ScriptResult.Failed -> if (!stop.get()) {
                    failure = RunFailure(result.reason, true, line = result.line)
                    log = result.log
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text(picked?.name ?: stringResource(R.string.effects_plugin_recipes)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (picked == null) {
                    if (several) Text(stringResource(R.string.plugin_script_selected, MultiSelect.ids.size), Modifier.padding(bottom = 6.dp), color = Am.OnDarkDim, fontSize = 13.sp)
                    val found = tools
                    if (found != null && found.isEmpty()) Text(stringResource(R.string.plugin_recipe_none), color = Am.OnDarkDim, fontSize = 14.sp)
                    for (entry in found.orEmpty()) Column(
                        Modifier.fillMaxWidth().clickable { chosen = entry; failure = null }.padding(vertical = 8.dp),
                    ) {
                        Text(entry.name, color = Am.OnDark, fontSize = 15.sp)
                        if (entry.description.isNotEmpty()) Text(entry.description, color = Am.OnDark, fontSize = 13.sp, lineHeight = 17.sp)
                        Text(entry.plugin.manifest?.name ?: entry.plugin.id, color = Am.OnDarkDim, fontSize = 12.sp)
                    }
                } else {
                    if (picked.description.isNotEmpty()) Text(picked.description, Modifier.padding(bottom = 6.dp), color = Am.OnDarkDim, fontSize = 13.sp, lineHeight = 17.sp)
                    for (input in picked.inputs) RecipeField(input, values[input.id] ?: input.default) { values[input.id] = it }
                    if (running) Text(stringResource(R.string.plugin_script_running), Modifier.padding(top = 8.dp), color = Am.OnDarkDim, fontSize = 14.sp)
                    failure?.let { failed ->
                        val reason = when {
                            failed.changed -> stringResource(R.string.plugin_script_changed)
                            failed.step != null -> stringResource(R.string.plugin_recipe_failed_step, failed.step + 1, failed.reason)
                            failed.line != null -> stringResource(R.string.plugin_script_line, failed.line, failed.reason)
                            else -> failed.reason
                        }
                        val message = if (failed.changed) reason else stringResource(if (failed.script) R.string.plugin_script_failed else R.string.plugin_recipe_failed, reason)
                        Text(message, Modifier.padding(top = 8.dp), color = Am.Accent, fontSize = 14.sp)
                    }
                    for (line in log) Text(line, Modifier.padding(top = 4.dp), color = Am.OnDarkDim, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (picked != null && finished) TextButton(onClose) { Text(stringResource(R.string.done), color = Am.Accent) }
            else if (picked != null && !running) TextButton({
                when (picked) {
                    is PluginTool.OfRecipe -> runRecipe(picked)
                    is PluginTool.OfScript -> runScript(picked.script)
                }
            }) { Text(stringResource(R.string.plugin_recipe_run), color = Am.Accent) }
        },
        dismissButton = {
            if (!finished) TextButton({ if (running) stop.set(true) else onClose() }) { Text(stringResource(R.string.cancel), color = Am.Accent) }
        },
        containerColor = Am.Field, titleContentColor = Am.OnDark, textContentColor = Am.OnDark,
    )
}

@Composable
private fun RecipeField(input: RecipeInput, value: String, set: (String) -> Unit) {
    when (input.type) {
        "switch" -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(input.label, Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
            ThinSwitch(on = value == "true") { set(it.toString()) }
        }
        "choice" -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(input.label, color = Am.OnDarkDim, fontSize = 12.sp)
            for (option in input.options) Text(
                option, Modifier.fillMaxWidth().clickable { set(option) }.padding(vertical = 8.dp),
                color = if (option == value) Am.Accent else Am.OnDark, fontSize = 15.sp,
            )
        }
        else -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(input.label, Modifier.padding(end = 12.dp), color = Am.OnDark, fontSize = 14.sp)
            BasicTextField(
                value, { set(it.replace('\n', ' ').take(200)) }, Modifier.weight(1f).clip(PanelShape).background(Color(0x33000000)).padding(horizontal = 10.dp, vertical = 6.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (input.type == "number") KeyboardType.Decimal else KeyboardType.Text),
                textStyle = TextStyle(color = Am.OnDark, fontSize = 14.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Content).withUiFont(),
                cursorBrush = SolidColor(Am.Accent),
            )
        }
    }
}
