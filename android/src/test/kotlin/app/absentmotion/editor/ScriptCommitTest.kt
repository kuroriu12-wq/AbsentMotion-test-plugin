package app.absentmotion.editor

import app.absentmotion.model.Scene
import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCommitTest {
    private val original = sceneOf(shapeLayer().node)
    private val edited = original.update(1) { it.withLabel("scripted") }

    private fun history(shown: Array<Scene>) = EditHistory({ shown[0] }) { scene, _ -> shown[0] = scene }

    @Test fun aRunCommitsOneHistoryEntryAndOneUndoRestoresTheScene() {
        val shown = arrayOf(original)
        val history = history(shown)
        val committed = commitScriptResult(original, shown[0], edited) { history.edit(null, it) }
        assertTrue(committed)
        assertEquals("scripted", shown[0].layer(1)!!.label)
        assertNotNull(history.undo())
        assertEquals(original.node, shown[0].node)
        assertFalse(history.canUndo)
    }

    @Test fun aChangedSceneCommitsNothing() {
        val shown = arrayOf(original.update(1) { it.withLabel("other edit") })
        val history = history(shown)
        val committed = commitScriptResult(original, shown[0], edited) { history.edit(null, it) }
        assertFalse(committed)
        assertEquals("other edit", shown[0].layer(1)!!.label)
        assertFalse(history.canUndo)
    }
}
