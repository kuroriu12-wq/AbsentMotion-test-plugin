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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
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
import app.absentmotion.plugins.PluginRecipe
import app.absentmotion.plugins.PluginStore
import app.absentmotion.plugins.RecipeInput
import app.absentmotion.plugins.RecipeRunner
import app.absentmotion.plugins.RunResult
import app.absentmotion.plugins.listRecipes
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.withUiFont

@Composable
internal fun PluginRecipeDialog(state: EditorState, onClose: () -> Unit) {
    val context = LocalContext.current
    val recipes = remember { listRecipes(PluginStore(context)) }
    val catalog by state.engine.effects.collectAsState()
    var chosen by remember { mutableStateOf<PluginRecipe?>(null) }
    var failure by remember { mutableStateOf<RunResult.Failed?>(null) }
    val values = remember(chosen) { mutableStateMapOf<String, String>().also { map -> chosen?.recipe?.inputs?.forEach { map[it.id] = it.default } } }
    val picked = chosen
    fun run() {
        val layer = state.selected ?: return
        val recipe = picked?.recipe ?: return
        state.playing = false
        when (val result = RecipeRunner(catalog).run(state.scene, layer.id, recipe, values.toMap(), state.sampleMs)) {
            is RunResult.Done -> {
                state.edit { result.scene }
                onClose()
            }
            is RunResult.Failed -> failure = result
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(picked?.recipe?.name ?: stringResource(R.string.effects_plugin_recipes)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (picked == null) {
                    if (recipes.isEmpty()) Text(stringResource(R.string.plugin_recipe_none), color = Am.OnDarkDim, fontSize = 14.sp)
                    for (entry in recipes) Column(
                        Modifier.fillMaxWidth().clickable { chosen = entry; failure = null }.padding(vertical = 8.dp),
                    ) {
                        Text(entry.recipe.name, color = Am.OnDark, fontSize = 15.sp)
                        Text(entry.plugin.manifest?.name ?: entry.plugin.id, color = Am.OnDarkDim, fontSize = 12.sp)
                    }
                } else {
                    for (input in picked.recipe.inputs) RecipeField(input, values[input.id] ?: input.default) { values[input.id] = it }
                    failure?.let { failed ->
                        val reason = failed.stepIndex?.let { stringResource(R.string.plugin_recipe_failed_step, it + 1, failed.reason) } ?: failed.reason
                        Text(stringResource(R.string.plugin_recipe_failed, reason), Modifier.padding(top = 8.dp), color = Am.Accent, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (picked != null) TextButton({ run() }) { Text(stringResource(R.string.plugin_recipe_run), color = Am.Accent) }
        },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.cancel), color = Am.Accent) } },
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
