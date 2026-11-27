package app.absentmotion.editor

import app.absentmotion.model.Scene

internal fun commitScriptResult(start: Scene, current: Scene, result: Scene, edit: (Scene) -> Unit): Boolean {
    if (current.node !== start.node) return false
    edit(result)
    return true
}

fun EditorState.scriptSelection(): List<Long> =
    (if (MultiSelect.active) MultiSelect.ids.toList() else emptyList()).ifEmpty { listOfNotNull(selectedId) }

fun EditorState.commitScript(start: Scene, result: Scene): Boolean =
    commitScriptResult(start, scene, result) { next -> edit { next } }
