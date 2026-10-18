package dev.sluide.httpclient.envedit

import tools.jackson.databind.JsonNode

/** A value converted to another type; [lossy] if it could not be converted and was replaced by an empty value. */
internal data class Conversion(
    val value: JsonNode,
    val lossy: Boolean,
)

/** Types of values, their empty values and conversions between them. */
internal object EnvValues {
    fun typeOf(node: JsonNode): ValueType =
        when {
            node.isObject -> ValueType.OBJECT
            node.isArray -> ValueType.LIST
            node.isString -> ValueType.TEXT
            node.isNumber -> ValueType.NUMBER
            node.isBoolean -> ValueType.YES_NO
            else -> ValueType.NULL
        }

    /** The value a new key of [type] starts with: `""`, `0`, `false`, `null`, `{}` or `[]`. */
    fun emptyValue(type: ValueType): JsonNode =
        when (type) {
            ValueType.TEXT -> EnvJson.nodes.stringNode("")
            ValueType.NUMBER -> EnvJson.nodes.numberNode(0)
            ValueType.YES_NO -> EnvJson.nodes.booleanNode(false)
            ValueType.NULL -> EnvJson.nodes.nullNode()
            ValueType.OBJECT -> EnvJson.nodes.objectNode()
            ValueType.LIST -> EnvJson.nodes.arrayNode()
            ValueType.MIXED -> throw IllegalArgumentException("Mixed is not a type of a value")
        }

    /**
     * [node] as [type]: texts that are numbers become numbers (keeping their spelling), `true`/`false` texts (any
     * case) become yes/no, numbers and yes/no become texts; what cannot be converted becomes the type's [emptyValue],
     * lossy unless [node] was empty itself (`null`, `""`, `{}`, `[]`).
     */
    fun convert(
        node: JsonNode,
        type: ValueType,
    ): Conversion {
        if (typeOf(node) == type) return Conversion(node, false)
        val converted =
            when (type) {
                ValueType.TEXT -> {
                    when {
                        node.isNumber -> EnvJson.nodes.stringNode(EnvJson.numberText(node))
                        node.isBoolean -> EnvJson.nodes.stringNode(node.booleanValue().toString())
                        else -> null
                    }
                }

                ValueType.NUMBER -> {
                    if (node.isString) EnvJson.number(node.stringValue()) else null
                }

                ValueType.YES_NO -> {
                    node
                        .takeIf { it.isString }
                        ?.stringValue()
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it == "true" || it == "false" }
                        ?.let { EnvJson.nodes.booleanNode(it == "true") }
                }

                else -> {
                    null
                }
            }
        return converted?.let { Conversion(it, false) } ?: Conversion(emptyValue(type), !isEmpty(node))
    }

    private fun isEmpty(node: JsonNode) =
        node.isNull || (node.isString && node.stringValue().isEmpty()) || (node.isContainer && node.isEmpty)
}
