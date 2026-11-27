package app.absentmotion.plugins

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class RecipeInput(
    val id: String,
    val label: String,
    val type: String,
    val default: String,
    val options: List<String> = emptyList(),
    val min: Float? = null,
    val max: Float? = null,
)

sealed interface RecipeStep {
    data class AddEffect(val effect: String, val preset: String?, val set: Map<String, String>) : RecipeStep
    data class SetEffectParam(val effect: String, val param: String, val value: String) : RecipeStep

    data class KeyframeEffectParam(val effect: String, val param: String, val at: List<Float>, val values: List<String>) : RecipeStep
}

data class Recipe(val id: String, val name: String, val inputs: List<RecipeInput>, val steps: List<RecipeStep>, val description: String = "")

sealed interface RecipeParse {
    data class Valid(val recipe: Recipe) : RecipeParse
    data class Invalid(val problems: List<String>) : RecipeParse
}

private val InputTypes = setOf("number", "color", "choice", "text", "switch")
private val InputId = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun scalar(value: Any?): String? = when (value) {
    is String -> value
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> null
}

fun parseRecipe(json: String): RecipeParse {
    val root = try {
        JSONObject(json.removePrefix("\uFEFF"))
    } catch (e: JSONException) {
        return RecipeParse.Invalid(listOf("the recipe is not a valid JSON object: ${e.message}"))
    }
    val problems = ArrayList<String>()

    val id = (root.opt("id") as? String)?.takeIf { it.isNotBlank() }
    if (id == null) problems += "id is required"
    val name = (root.opt("name") as? String)?.takeIf { it.isNotBlank() }
    if (name == null) problems += "name is required"

    val inputs = parseRecipeInputs(root, problems)
    val inputIds = inputs.map { it.id }.toSet()

    fun step(index: Int, raw: Any?): RecipeStep? {
        val label = "step ${index + 1}"
        val o = raw as? JSONObject ?: run {
            problems += "$label must be an object"
            return null
        }
        val before = problems.size

        fun text(key: String): String {
            val value = (o.opt(key) as? String)?.takeIf { it.isNotBlank() }
            if (value == null) problems += "$label: $key is required"
            return value ?: ""
        }

        fun reference(value: String): String {
            if (value.startsWith("$") && value.drop(1) !in inputIds) problems += "$label: '$value' is not an input of this recipe"
            return value
        }

        val kind: Any? = o.opt("do")
        val step: RecipeStep? = when {
            kind == "addEffect" -> {
                val effect = text("effect")
                var preset: String? = null
                if (o.has("preset")) {
                    preset = o.opt("preset") as? String
                    if (preset == null) problems += "$label: preset must be text"
                }
                val set = LinkedHashMap<String, String>()
                if (o.has("set")) {
                    val settings = o.opt("set") as? JSONObject
                    if (settings == null) {
                        problems += "$label: set must be an object"
                    } else {
                        for (key in settings.keys().asSequence().toList()) {
                            val value = scalar(settings.opt(key))
                            if (value == null) problems += "$label: set.$key must be text, a number or true/false" else set[key] = reference(value)
                        }
                    }
                }
                RecipeStep.AddEffect(effect, preset, set)
            }
            kind == "setEffectParam" -> {
                val value = scalar(o.opt("value"))
                if (value == null) problems += "$label: value is required"
                RecipeStep.SetEffectParam(text("effect"), text("param"), reference(value ?: ""))
            }
            kind == "keyframeEffectParam" -> {
                val effect = text("effect")
                val param = text("param")
                val at = ArrayList<Float>()
                val atArray = o.opt("at") as? JSONArray
                if (atArray == null || atArray.length() == 0) {
                    problems += "$label: at must be a list of seconds with at least one entry"
                } else {
                    for (i in 0 until atArray.length()) {
                        val seconds = (atArray.opt(i) as? Number)?.toFloat()?.takeIf { it.isFinite() }
                        if (seconds == null) problems += "$label: at[$i] must be a number" else at += seconds
                    }
                }
                val values = ArrayList<String>()
                val valuesArray = o.opt("values") as? JSONArray
                if (valuesArray == null || valuesArray.length() == 0) {
                    problems += "$label: values must be a list with at least one entry"
                } else {
                    for (i in 0 until valuesArray.length()) {
                        val value = scalar(valuesArray.opt(i))
                        if (value == null) problems += "$label: values[$i] must be text, a number or true/false" else values += reference(value)
                    }
                    if (atArray != null && atArray.length() != valuesArray.length()) problems += "$label: at and values need the same number of entries"
                }
                RecipeStep.KeyframeEffectParam(effect, param, at, values)
            }
            else -> {
                problems += "$label: unknown step '$kind'"
                null
            }
        }
        return if (problems.size > before) null else step
    }

    val steps = ArrayList<RecipeStep>()
    val stepsArray = root.opt("steps") as? JSONArray
    if (stepsArray == null || stepsArray.length() == 0) {
        problems += "steps must be a list with at least one step"
    } else {
        for (i in 0 until stepsArray.length()) step(i, stepsArray.opt(i))?.let { steps += it }
    }

    val description = optionalText(root, "description", 200, "description", problems)
    if (problems.isNotEmpty() || id == null || name == null) return RecipeParse.Invalid(problems)
    return RecipeParse.Valid(Recipe(id, name, inputs, steps, description))
}

internal fun parseRecipeInput(index: Int, raw: Any?, problems: MutableList<String>): RecipeInput? {
    val label = "input ${index + 1}"
    val o = raw as? JSONObject ?: run {
        problems += "$label must be an object"
        return null
    }
    val before = problems.size
    val inputId = (o.opt("id") as? String)?.takeIf { InputId.matches(it) }
    if (inputId == null) problems += "$label: id must start with a letter or underscore and use letters, digits and underscores"
    val text = (o.opt("label") as? String)?.takeIf { it.isNotBlank() }
    if (text == null) problems += "$label: label is required"
    val type = (o.opt("type") as? String)?.takeIf { it in InputTypes }
    if (type == null) problems += "$label: type must be one of ${InputTypes.joinToString()}"
    val default = scalar(o.opt("default"))
    if (default == null) problems += "$label: default is required"

    val options = ArrayList<String>()
    if (o.has("options")) {
        val array = o.opt("options") as? JSONArray
        if (array == null) {
            problems += "$label: options must be a list"
        } else {
            for (i in 0 until array.length()) {
                val option = array.opt(i) as? String
                if (option == null) problems += "$label: options[$i] must be a string" else options += option
            }
        }
    }
    fun bound(key: String): Float? {
        if (!o.has(key)) return null
        val number = (o.opt(key) as? Number)?.toFloat()?.takeIf { it.isFinite() }
        if (number == null) problems += "$label: $key must be a number"
        return number
    }
    val min = bound("min")
    val max = bound("max")
    if (min != null && max != null && min > max) problems += "$label: min is above max"

    if (type == "number" && default != null && default.toFloatOrNull()?.isFinite() != true) problems += "$label: default must be a number"
    if (type == "choice") {
        if (options.isEmpty()) problems += "$label: a choice needs options"
        else if (default != null && default !in options) problems += "$label: default must be one of the options"
    }
    if (problems.size > before || inputId == null || text == null || type == null || default == null) return null
    return RecipeInput(inputId, text, type, default, options, min, max)
}

internal fun parseRecipeInputs(root: JSONObject, problems: MutableList<String>): List<RecipeInput> {
    val inputs = ArrayList<RecipeInput>()
    if (root.has("inputs")) {
        val array = root.opt("inputs") as? JSONArray
        if (array == null) {
            problems += "inputs must be a list"
        } else {
            for (i in 0 until array.length()) {
                val parsed = parseRecipeInput(i, array.opt(i), problems) ?: continue
                if (inputs.any { it.id == parsed.id }) problems += "input ${i + 1}: id '${parsed.id}' is used twice" else inputs += parsed
            }
        }
    }
    return inputs
}
