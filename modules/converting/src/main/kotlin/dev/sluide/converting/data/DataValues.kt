package dev.sluide.converting.data

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.BigIntegerNode
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.DecimalNode
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.LongNode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal
import java.math.BigInteger

private val nodes = JsonNodeFactory.instance

private val integer = Regex("0|-?[1-9][0-9]*")
private val decimal = Regex("-?(?:0|[1-9][0-9]*)\\.[0-9]+")

/**
 * Numbers with more digits stay text: parsing them takes quadratic time, and Jackson refuses longer ones
 * (`StreamReadConstraints.maxNumberLength`).
 */
internal const val MAX_NUMBER_DIGITS = 1000

/** Whether [text] has too many digits to be read as a number ([MAX_NUMBER_DIGITS]). */
internal fun tooLongForNumber(text: String) =
    text.length > MAX_NUMBER_DIGITS && text.count(Char::isDigit) > MAX_NUMBER_DIGITS

internal val keyOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER.then(naturalOrder())

/**
 * The node of a value written as text, for formats whose values are strings ("Infer types"): integers and decimals
 * (exact) and `true`/`false`. Leading zeros (`0123`), a leading `+`, `-0` and `-0.0`, exponents (`1e3`), numbers of
 * more than [MAX_NUMBER_DIGITS] digits and everything else stay text, so that nothing is changed by reading it as a
 * number.
 */
internal fun inferScalar(text: String): JsonNode =
    when {
        text == "true" -> {
            BooleanNode.TRUE
        }

        text == "false" -> {
            BooleanNode.FALSE
        }

        tooLongForNumber(text) -> {
            StringNode.valueOf(text)
        }

        integer.matches(text) -> {
            integerNode(BigInteger(text))
        }

        decimal.matches(text) && !(text.startsWith("-") && BigDecimal(text).signum() == 0) -> {
            DecimalNode.valueOf(BigDecimal(text))
        }

        else -> {
            StringNode.valueOf(text)
        }
    }

/** The node Jackson reads an integer as: an int node if it fits, else a long node, else a big integer node. */
internal fun integerNode(value: BigInteger): JsonNode =
    when (value.bitLength()) {
        in 0..31 -> IntNode.valueOf(value.toInt())
        in 32..63 -> LongNode.valueOf(value.toLong())
        else -> BigIntegerNode.valueOf(value)
    }

/** A copy of [node] with every object's keys sorted case-insensitively, then naturally; arrays keep their order. */
internal fun sortKeys(node: JsonNode): JsonNode =
    when {
        node.isObject -> {
            val sorted = nodes.objectNode()
            node.properties().sortedWith(compareBy(keyOrder) { it.key }).forEach { (key, value) ->
                sorted.set(key, sortKeys(value))
            }
            sorted
        }

        node.isArray -> {
            nodes.arrayNode().apply { node.forEach { add(sortKeys(it)) } }
        }

        else -> {
            node
        }
    }

/** A note on [count] things: "1 comment dropped", "3 comments dropped". */
internal fun counted(
    count: Int,
    one: String,
    many: String,
): String = "$count ${if (count == 1) one else many}"

/** Whether [node] has containers nested more than [limit] levels deep; iterative, as a deep tree breaks recursion. */
internal fun isNestedBeyond(
    node: JsonNode,
    limit: Int,
): Boolean {
    val pending = ArrayDeque<Pair<JsonNode, Int>>()
    pending.addLast(node to 0)
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        if (depth > limit) return true
        current.forEach { pending.addLast(it to depth + 1) }
    }
    return false
}
