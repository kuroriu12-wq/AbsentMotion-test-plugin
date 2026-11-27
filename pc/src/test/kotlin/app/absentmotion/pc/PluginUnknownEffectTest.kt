package app.absentmotion.pc

import app.absentmotion.pc.engine.DesktopNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUnknownEffectTest {
    private fun scene(effect: String) =
        """<scene width="400" height="400" totalTime="999" fps="30" bgcolor="#ff000000"><shape id="1" startTime="0" endTime="999" fillType="color" s=".rect"><transform><location value="200,200,0" /></transform><fillColor value="#ffffffff" /><property name="size" type="vec2" value="100,50" />$effect</shape></scene>"""

    private fun whitePixels(xml: String): Int {
        val handle = DesktopNative.create()
        try {
            assertEquals(null, DesktopNative.setScene(handle, xml))
            val rgba = DesktopNative.still(handle, 0.0, 400, 400, true)!!
            var count = 0
            for (i in 0 until 400 * 400) if ((rgba[i * 4].toInt() and 255) > 128) count++
            return count
        } finally {
            DesktopNative.destroy(handle)
        }
    }

    @Test fun aLayerWithAnEffectNobodyLoadedStillDrawsAsIfItWereNotThere() {
        val plain = whitePixels(scene(""))
        val unknown = whitePixels(scene("""<effect id="kuep.gone.pulse" hidden="false"><property name="amount" type="float" value="0.5" /></effect>"""))
        assertTrue("the shape should draw", plain > 0)
        assertEquals(plain, unknown)
    }
}
