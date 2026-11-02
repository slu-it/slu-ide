package dev.sluide.databases.result

import dev.sluide.databases.session.ColumnInfo
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAccessor
import java.util.UUID

/** Text of result values: in the grid cell, in the value viewer and copy, and as typed into an edited cell. */
object CellFormat {
    const val NULL_TEXT = "<null>"

    private const val ELLIPSIS = "…"
    private const val LINE_BREAK = "⏎"
    private const val HEX = "0123456789abcdef"
    private const val ARRAY_SPECIAL = ",{}\"\\"

    /** Types typed in as text although their JDBC type says otherwise: `money`. */
    private val TEXT_TYPES = setOf("money")

    /**
     * One-line cell text: null → [NULL_TEXT]; ByteArray → "0x" + hex (at most [maxChars], then "…"); line breaks →
     * "⏎"; text cut at [maxChars] with "…"; BigDecimal plain; java.time ISO; List → PostgreSQL array literal `{a,"b c",NULL}`; else
     * toString.
     */
    fun display(
        value: Any?,
        maxChars: Int = 1000,
    ): String {
        if (value == null) return NULL_TEXT
        val text =
            when (value) {
                is ByteArray -> "0x" + hex(value, maxChars / 2 + 1)
                else -> text(value).replace("\r\n", LINE_BREAK).replace("\n", LINE_BREAK).replace("\r", LINE_BREAK)
            }
        return if (text.length > maxChars) text.take(maxChars) + ELLIPSIS else text
    }

    /** The complete value as text (value viewer, copy): null → ""; ByteArray full hex with "0x"; no cutting, line breaks kept. */
    fun full(value: Any?): String = if (value == null) "" else text(value)

    /**
     * Text typed into a cell → the value to bind for [column], by its jdbcType: integers → Long (BigDecimal when
     * larger), other numbers → BigDecimal, booleans ("true/false/1/0/t/f"), dates and times ISO (a space instead of
     * the `T` is accepted), binary "0x…" hex, PostgreSQL `uuid` → UUID, `timestamptz` → OffsetDateTime (a plain timestamp refuses an offset),
     * `timetz` → OffsetTime, `money`, `bit` and `varbit` and everything else → the text itself. Blank text for a
     * non-text column is null. Throws [IllegalArgumentException] with a short UI message when it doesn't parse.
     */
    fun parse(
        text: String,
        column: ColumnInfo,
    ): Any? {
        val kind = kindOf(column)
        if (kind == Kind.TEXT) return text
        val t = text.trim()
        if (t.isEmpty()) return null
        return when (kind) {
            Kind.INTEGER -> {
                parseInteger(t)
            }

            Kind.DECIMAL -> {
                parseDecimal(t)
            }

            Kind.BOOLEAN -> {
                parseBoolean(t)
            }

            Kind.DATE -> {
                attempt("Not a date (yyyy-mm-dd)") { LocalDate.parse(t) }
            }

            Kind.TIME -> {
                attempt("Not a time (hh:mm:ss)") {
                    try {
                        LocalTime.parse(t)
                    } catch (_: DateTimeParseException) {
                        OffsetTime.parse(t)
                    }
                }
            }

            Kind.TIMESTAMP -> {
                parseTimestamp(t)
            }

            Kind.TIMESTAMP_TZ -> {
                attempt("Not a timestamp with offset (yyyy-mm-ddThh:mm:ss+hh:mm)") {
                    OffsetDateTime.parse(t.replaceFirst(' ', 'T'))
                }
            }

            Kind.BINARY -> {
                parseBinary(t)
            }

            Kind.UUID -> {
                attempt("Not a UUID") { UUID.fromString(t) }
            }

            Kind.TEXT -> {
                text
            }
        }
    }

    private enum class Kind { INTEGER, DECIMAL, BOOLEAN, DATE, TIME, TIMESTAMP, TIMESTAMP_TZ, BINARY, UUID, TEXT }

    private fun kindOf(column: ColumnInfo): Kind {
        val type = column.typeName.lowercase()
        return when {
            type in TEXT_TYPES -> Kind.TEXT

            // PostgreSQL names are lower case; MariaDB's BIT(1) stays a boolean.
            column.typeName == "bit" || column.typeName == "varbit" -> Kind.TEXT

            type == "timestamptz" -> Kind.TIMESTAMP_TZ

            type == "uuid" -> Kind.UUID

            else -> kindOfJdbcType(column.jdbcType)
        }
    }

    private fun kindOfJdbcType(jdbcType: Int): Kind =
        when (jdbcType) {
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> Kind.INTEGER
            Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE -> Kind.DECIMAL
            Types.BOOLEAN, Types.BIT -> Kind.BOOLEAN
            Types.DATE -> Kind.DATE
            Types.TIME -> Kind.TIME
            Types.TIMESTAMP -> Kind.TIMESTAMP
            Types.TIMESTAMP_WITH_TIMEZONE -> Kind.TIMESTAMP_TZ
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Kind.BINARY
            else -> Kind.TEXT
        }

    private inline fun attempt(
        message: String,
        block: () -> Any,
    ): Any =
        try {
            block()
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException(message)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(message)
        }

    private fun parseInteger(t: String): Any {
        val big = t.removePrefix("+").toBigIntegerOrNull() ?: throw IllegalArgumentException("Not a whole number")
        return if (big.bitLength() < 64) big.toLong() else BigDecimal(big)
    }

    private fun parseDecimal(t: String): Any =
        try {
            BigDecimal(t.removePrefix("+"))
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Not a number")
        }

    private fun parseBoolean(t: String): Any =
        when (t.lowercase()) {
            "true", "1", "t" -> true
            "false", "0", "f" -> false
            else -> throw IllegalArgumentException("Not a boolean (true or false)")
        }

    private fun parseTimestamp(t: String): Any =
        attempt("Not a timestamp (yyyy-mm-dd hh:mm:ss)") { LocalDateTime.parse(t.replaceFirst(' ', 'T')) }

    private fun parseBinary(t: String): ByteArray {
        val message = "Binary values are typed as 0x followed by hex digits"
        if (!t.startsWith("0x", ignoreCase = true)) throw IllegalArgumentException(message)
        val digits = t.substring(2)
        if (digits.length % 2 != 0 ||
            digits.any { Character.digit(it, 16) < 0 }
        ) {
            throw IllegalArgumentException(message)
        }
        return ByteArray(digits.length / 2) {
            ((Character.digit(digits[it * 2], 16) shl 4) or Character.digit(digits[it * 2 + 1], 16)).toByte()
        }
    }

    private fun text(value: Any): String =
        when (value) {
            is String -> value
            is BigDecimal -> value.toPlainString()
            is BigInteger -> value.toString()
            is ByteArray -> "0x" + hex(value, value.size)
            is LocalDate -> DateTimeFormatter.ISO_LOCAL_DATE.format(value)
            is LocalTime -> DateTimeFormatter.ISO_LOCAL_TIME.format(value)
            is LocalDateTime -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value)
            is OffsetDateTime -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value)
            is OffsetTime -> DateTimeFormatter.ISO_OFFSET_TIME.format(value)
            is TemporalAccessor -> value.toString()
            is List<*> -> value.joinToString(",", "{", "}") { arrayElement(it) }
            else -> value.toString()
        }

    /** One element of a PostgreSQL array literal: quoted when empty, spelled like NULL or holding `,{}"\\` or whitespace. */
    private fun arrayElement(element: Any?): String {
        if (element == null) return "NULL"
        if (element is List<*>) return text(element)
        val text = text(element)
        val plain =
            text.isNotEmpty() && !text.equals("NULL", ignoreCase = true) &&
                text.none { it in ARRAY_SPECIAL || it.isWhitespace() }
        return if (plain) text else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** Lower-case hex of the first [maxBytes] bytes. */
    internal fun hex(
        bytes: ByteArray,
        maxBytes: Int = bytes.size,
    ): String {
        val n = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xff
            sb.append(HEX[b shr 4]).append(HEX[b and 0xf])
        }
        return sb.toString()
    }
}
