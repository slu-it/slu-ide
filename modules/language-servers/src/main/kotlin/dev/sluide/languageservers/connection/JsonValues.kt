package dev.sluide.languageservers.connection

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * [value] as JSON, built without reflection: maps with string keys, lists (any collection), strings, numbers,
 * booleans, `null` and Gson's own [JsonElement]s. Anything else (a Jackson node, a data class) would come out of Gson's
 * reflection as garbage, so it is refused.
 *
 * @throws IllegalArgumentException naming the first value of another type.
 */
internal fun jsonTree(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> jsonObject(value)
        is Collection<*> -> JsonArray().apply { value.forEach { add(jsonTree(it)) } }
        else -> throw IllegalArgumentException(notJson(value))
    }

private fun jsonObject(map: Map<*, *>) =
    JsonObject().apply {
        map.forEach { (key, item) ->
            require(key is String) { "JSON object keys must be strings, not ${key?.javaClass?.name}" }
            add(key, jsonTree(item))
        }
    }

private fun notJson(value: Any) =
    "Not a JSON value: ${value.javaClass.name} (use Map, List, String, Number, Boolean, null or a Gson JsonElement)"
