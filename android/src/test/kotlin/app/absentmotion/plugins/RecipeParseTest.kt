package app.absentmotion.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeParseTest {
    private val d = "$"

    private fun recipe(inputs: String = "", steps: String): String =
        """{"id":"shake","name":"Shake","inputs":[$inputs],"steps":[$steps]}"""

    private val strength = """{"id":"strength","label":"Strength","type":"number","default":"7","min":0,"max":20}"""
    private val add = """{"do":"addEffect","effect":"absent.glitch","preset":"Name","set":{"intensity":"${d}strength"}}"""
    private val set = """{"do":"setEffectParam","effect":"absent.glitch","param":"intensity","value":"${d}strength"}"""
    private val plain = """{"do":"addEffect","effect":"absent.glitch"}"""
    private val keys = """{"do":"keyframeEffectParam","effect":"absent.glitch","param":"intensity","at":[0,0.3],"values":[1.2,1.0]}"""

    private fun invalid(json: String) = parseRecipe(json) as? RecipeParse.Invalid

    @Test fun parsesAllThreeStepTypes() {
        val result = parseRecipe(recipe(strength, "$add,$set,$keys")) as RecipeParse.Valid
        assertEquals("shake", result.recipe.id)
        assertEquals("Shake", result.recipe.name)
        assertEquals(
            listOf(RecipeInput("strength", "Strength", "number", "7", min = 0f, max = 20f)),
            result.recipe.inputs,
        )
        assertEquals(
            listOf(
                RecipeStep.AddEffect("absent.glitch", "Name", mapOf("intensity" to "${d}strength")),
                RecipeStep.SetEffectParam("absent.glitch", "intensity", "${d}strength"),
                RecipeStep.KeyframeEffectParam("absent.glitch", "intensity", listOf(0f, 0.3f), listOf("1.2", "1.0")),
            ),
            result.recipe.steps,
        )
    }

    @Test fun inputsAreOptionalAndPresetIsOptional() {
        val result = parseRecipe("""{"id":"a","name":"A","steps":[{"do":"addEffect","effect":"absent.glitch"}]}""") as RecipeParse.Valid
        assertEquals(emptyList<RecipeInput>(), result.recipe.inputs)
        assertEquals(RecipeStep.AddEffect("absent.glitch", null, emptyMap()), result.recipe.steps.single())
    }

    @Test fun unknownStepIsInvalidAndNamesTheIndex() {
        val result = invalid(recipe(strength, """$add,{"do":"explode"}"""))!!
        assertTrue(result.problems.any { "step 2" in it && "explode" in it })
    }

    @Test fun undefinedInputReferenceIsInvalid() {
        val result = invalid(recipe("", """{"do":"setEffectParam","effect":"e","param":"p","value":"${d}nope"}"""))!!
        assertTrue(result.problems.any { "nope" in it })
        assertTrue(invalid(recipe(strength, """{"do":"keyframeEffectParam","effect":"e","param":"p","at":[0],"values":["${d}nope"]}""")) != null)
        assertTrue(invalid(recipe(strength, """{"do":"addEffect","effect":"e","set":{"p":"${d}nope"}}""")) != null)
    }

    @Test fun duplicateInputIdsInvalid() {
        assertTrue(invalid(recipe("$strength,$strength", plain)) != null)
    }

    @Test fun unknownInputTypeInvalid() {
        val slider = """{"id":"s","label":"S","type":"slider","default":"1"}"""
        assertTrue(invalid(recipe(slider, plain)) != null)
    }

    @Test fun numberInputNeedsANumericDefault() {
        val text = """{"id":"s","label":"S","type":"number","default":"abc"}"""
        assertTrue(invalid(recipe(text, plain)) != null)
    }

    @Test fun choiceNeedsOptionsAndDefaultInOptions() {
        val none = """{"id":"c","label":"C","type":"choice","default":"a"}"""
        val missing = """{"id":"c","label":"C","type":"choice","default":"z","options":["a","b"]}"""
        val ok = """{"id":"c","label":"C","type":"choice","default":"b","options":["a","b"]}"""
        assertTrue(invalid(recipe(none, plain)) != null)
        assertTrue(invalid(recipe(missing, plain)) != null)
        val result = parseRecipe(recipe(ok, plain)) as RecipeParse.Valid
        assertEquals(listOf("a", "b"), result.recipe.inputs.single().options)
    }

    @Test fun keyframeNeedsMatchingNonEmptyLists() {
        fun keyed(at: String, values: String) = recipe("", """{"do":"keyframeEffectParam","effect":"e","param":"p","at":[$at],"values":[$values]}""")
        assertTrue(invalid(keyed("0,1", "5")) != null)
        assertTrue(invalid(keyed("", "")) != null)
        assertTrue(invalid(keyed("\"x\"", "1")) != null)
        assertTrue(parseRecipe(keyed("0,1", "5,6")) is RecipeParse.Valid)
    }

    @Test fun aRecipeNeedsAtLeastOneStep() {
        assertTrue(invalid("""{"id":"a","name":"A","steps":[]}""") != null)
        assertTrue(invalid("""{"id":"a","name":"A"}""") != null)
    }

    @Test fun badJsonNeverThrows() {
        for (text in listOf("", "not json", "[]", "null", "{}", "﻿{")) {
            assertTrue("'$text' should be invalid", invalid(text) != null)
        }
        assertTrue(parseRecipe("﻿" + recipe(strength, add)) is RecipeParse.Valid)
    }

    @Test fun descriptionIsOptionalAndLimited() {
        val plainRecipe = (parseRecipe(recipe(steps = plain)) as RecipeParse.Valid).recipe
        assertEquals("", plainRecipe.description)
        val described = (parseRecipe("""{"id":"shake","name":"Shake","description":" Shakes the layer. ","steps":[$plain]}""") as RecipeParse.Valid).recipe
        assertEquals("Shakes the layer.", described.description)
        assertTrue(invalid("""{"id":"shake","name":"Shake","description":"${"x".repeat(201)}","steps":[$plain]}""") != null)
        assertTrue(invalid("""{"id":"shake","name":"Shake","description":4,"steps":[$plain]}""") != null)
    }
}
