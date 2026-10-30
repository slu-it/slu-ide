package dev.sluide.converting

/**
 * An option of reading or writing a format. Ids form one namespace across all families (`indent`, `sortKeys`,
 * `compact`, `escapeNonAscii`, `inferTypes`, `csvHeader`, `csvDelimiter`, …), so an option offered by several
 * formats keeps its value when the pair changes.
 */
sealed interface ConversionOption {
    val id: String
    val label: String

    /** On or off. */
    data class Toggle(
        override val id: String,
        override val label: String,
        val default: Boolean,
    ) : ConversionOption

    /** One of [choices]. */
    data class Choice(
        override val id: String,
        override val label: String,
        val choices: List<String>,
        val default: String,
    ) : ConversionOption
}

/** Option values by id; a missing or invalid value means the option's default. Immutable. */
class OptionValues(
    values: Map<String, Any> = emptyMap(),
) {
    private val values = values.toMap()

    fun flag(option: ConversionOption.Toggle): Boolean = values[option.id] as? Boolean ?: option.default

    fun choice(option: ConversionOption.Choice): String =
        (values[option.id] as? String)?.takeIf { it in option.choices } ?: option.default

    /** These values with [id] set to [value]. */
    fun with(
        id: String,
        value: Any,
    ): OptionValues = OptionValues(values + (id to value))

    override fun equals(other: Any?): Boolean = other is OptionValues && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "OptionValues($values)"
}
