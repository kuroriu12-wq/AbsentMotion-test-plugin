package app.absentmotion.plugins

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.Scene
import app.absentmotion.model.effectProperty
import app.absentmotion.model.effects
import app.absentmotion.model.keyframes
import app.absentmotion.model.valueAt
import app.absentmotion.sceneOf
import app.absentmotion.shapeLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeRunnerTest {
    private val catalog = EffectCatalog.parse { File("../shared/assets/effects", it).inputStream() }
    private val runner = RecipeRunner(catalog)
    private val d = "$"

    private val glitch = catalog.find("absent.glitch")!!
    private val intensity = glitch.params.first { it.id == "intensity" }

    private fun scene(vararg layers: app.absentmotion.model.Layer): Scene = sceneOf(*layers.map { it.node }.toTypedArray())

    private fun recipe(vararg steps: RecipeStep, inputs: List<RecipeInput> = emptyList()) = Recipe("r", "R", inputs, steps.toList())

    private fun number(id: String = "strength", default: String = "1", min: Float? = 0f, max: Float? = 20f) =
        RecipeInput(id, "Strength", "number", default, min = min, max = max)

    private val add = RecipeStep.AddEffect("absent.glitch", null, emptyMap())

    private fun run(scene: Scene, recipe: Recipe, inputs: Map<String, String> = emptyMap(), layerId: Long = 1) =
        runner.run(scene, layerId, recipe, inputs, 0)

    private fun layerOf(result: RunResult) = (result as RunResult.Done).scene.layer(1)!!

    @Test fun addEffectAppendsInstanceWithOverrides() {
        val step = RecipeStep.AddEffect("absent.glitch", null, mapOf("intensity" to "${d}strength"))
        val result = run(scene(shapeLayer()), recipe(step, inputs = listOf(number())), mapOf("strength" to "7"))
        val effect = layerOf(result).effects.single()
        assertEquals("absent.glitch", effect["id"])
        assertEquals(intensity.format(7f), effect.effectProperty("intensity")?.valueAt(0f))
    }

    @Test fun presetNameIsApplied() {
        val ascii = catalog.find("absent.ascii")!!
        val preset = ascii.presets.first()
        val result = run(scene(shapeLayer()), recipe(RecipeStep.AddEffect("absent.ascii", preset.name, emptyMap())))
        val effect = layerOf(result).effects.single()
        for ((key, value) in preset.values) assertEquals("preset value $key", value, effect.effectProperty(key)?.valueAt(0f))
        assertTrue(preset.values.isNotEmpty())
    }

    @Test fun anUnknownPresetFails() {
        val result = run(scene(shapeLayer()), recipe(RecipeStep.AddEffect("absent.ascii", "No Such Preset", emptyMap())))
        assertEquals(0, (result as RunResult.Failed).stepIndex)
    }

    @Test fun keyframeStepWritesNormalisedTimes() {
        val keys = RecipeStep.KeyframeEffectParam("absent.glitch", "intensity", listOf(0f, 0.3f), listOf("0.2", "0.9"))
        val effect = layerOf(run(scene(shapeLayer()), recipe(add, keys))).effects.single()
        val frames = effect.effectProperty("intensity")!!.keyframes
        assertEquals(listOf(0f, 0.15f), frames.map { it.t })
        assertEquals(listOf(intensity.format(0.2f), intensity.format(0.9f)), frames.map { it.v })
    }

    @Test fun stepsRunInOrderOnTheLastMatchingEffect() {
        val set = RecipeStep.SetEffectParam("absent.glitch", "intensity", "0.25")
        val keys = RecipeStep.KeyframeEffectParam("absent.glitch", "intensity", listOf(0f, 1f), listOf("0.1", "0.8"))
        val first = layerOf(run(scene(shapeLayer()), recipe(add, set)))
        assertEquals(intensity.format(0.25f), first.effects.single().effectProperty("intensity")?.valueAt(0f))
        val both = layerOf(run(scene(shapeLayer()), recipe(add, add, set, keys)))
        assertEquals(2, both.effects.size)
        assertEquals(intensity.format(0.5f), both.effects[0].effectProperty("intensity")?.valueAt(0f))
        assertEquals(2, both.effects[1].effectProperty("intensity")!!.keyframes.size)
    }

    @Test fun settingAnEffectTheLayerDoesNotHaveYetFails() {
        val set = RecipeStep.SetEffectParam("absent.glitch", "intensity", "0.25")
        val result = run(scene(shapeLayer()), recipe(set, add))
        assertEquals(0, (result as RunResult.Failed).stepIndex)
    }

    @Test fun defaultsFillMissingInputs() {
        val step = RecipeStep.AddEffect("absent.glitch", null, mapOf("intensity" to "${d}strength"))
        val result = run(scene(shapeLayer()), recipe(step, inputs = listOf(number(default = "0.4"))))
        assertEquals(intensity.format(0.4f), layerOf(result).effects.single().effectProperty("intensity")?.valueAt(0f))
    }

    @Test fun unknownEffectFailsAndNamesStep() {
        val original = scene(shapeLayer())
        val before = original.node
        val result = run(original, recipe(add, RecipeStep.AddEffect("absent.nope", null, emptyMap()))) as RunResult.Failed
        assertEquals(1, result.stepIndex)
        assertTrue(result.reason.contains("absent.nope"))
        assertEquals(before, original.node)
    }

    @Test fun unknownParameterFailsAndNamesStep() {
        val result = run(scene(shapeLayer()), recipe(add, RecipeStep.SetEffectParam("absent.glitch", "nope", "1"))) as RunResult.Failed
        assertEquals(1, result.stepIndex)
    }

    @Test fun badNumbersFailWithNullStep() {
        val step = RecipeStep.AddEffect("absent.glitch", null, mapOf("intensity" to "${d}strength"))
        val r = recipe(step, inputs = listOf(number(min = 0f, max = 10f)))
        for (bad in listOf("NaN", "Infinity", "-Infinity", "-1", "11", "abc", "")) {
            val result = run(scene(shapeLayer()), r, mapOf("strength" to bad))
            assertTrue("'$bad' should fail", result is RunResult.Failed)
            assertNull("'$bad' is an input problem, not a step problem", (result as RunResult.Failed).stepIndex)
        }
        assertTrue(run(scene(shapeLayer()), r, mapOf("strength" to "10")) is RunResult.Done)
    }

    @Test fun nonFiniteStepValuesFail() {
        for (bad in listOf("NaN", "Infinity")) {
            val result = run(scene(shapeLayer()), recipe(RecipeStep.AddEffect("absent.glitch", null, mapOf("intensity" to bad))))
            assertEquals(0, (result as RunResult.Failed).stepIndex)
        }
    }

    @Test fun switchAndChoiceInputsAreChecked() {
        val choice = RecipeInput("c", "C", "choice", "a", options = listOf("a", "b"))
        val toggle = RecipeInput("s", "S", "switch", "true")
        val r = recipe(add, inputs = listOf(choice, toggle))
        assertTrue(run(scene(shapeLayer()), r, mapOf("c" to "b", "s" to "false")) is RunResult.Done)
        assertNull((run(scene(shapeLayer()), r, mapOf("c" to "z")) as RunResult.Failed).stepIndex)
        assertNull((run(scene(shapeLayer()), r, mapOf("s" to "yes")) as RunResult.Failed).stepIndex)
    }

    @Test fun keyframesOutsideTheLayerFail() {
        val keys = RecipeStep.KeyframeEffectParam("absent.glitch", "intensity", listOf(0f, 3f), listOf("0.1", "0.2"))
        val result = run(scene(shapeLayer()), recipe(add, keys)) as RunResult.Failed
        assertEquals(1, result.stepIndex)
    }

    @Test fun missingOrLockedLayerFails() {
        val result = run(scene(shapeLayer()), recipe(add), layerId = 99)
        assertNull((result as RunResult.Failed).stepIndex)
        val locked = run(scene(shapeLayer(attrs = mapOf("lock" to "all"))), recipe(add)) as RunResult.Failed
        assertNull(locked.stepIndex)
    }

    @Test fun otherLayersAreLeftAlone() {
        val result = run(scene(shapeLayer(id = 1), shapeLayer(id = 2)), recipe(add)) as RunResult.Done
        assertEquals(1, result.scene.layer(1)!!.effects.size)
        assertEquals(0, result.scene.layer(2)!!.effects.size)
    }
}
