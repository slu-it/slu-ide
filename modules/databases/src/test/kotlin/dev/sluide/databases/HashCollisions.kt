package dev.sluide.databases

/**
 * Strings with the same `hashCode` as a given one. Kotlin compiles a `when` over string constants to a switch on the
 * hash code followed by an equality check, so only such a string can tell whether that check is there.
 */
internal object HashCollisions {
    /** A different string with the hash code of [word]: a leading NUL for one character, else the last two shifted so that lower-casing keeps it (c1 - 1, c2 + 31). */
    fun of(word: String): String {
        val other =
            if (word.length == 1) {
                "\u0000$word"
            } else {
                word.dropLast(2) + (word[word.length - 2] - 1) + (word.last() + 31)
            }
        check(other != word && other.hashCode() == word.hashCode()) { "no collision for $word" }
        return other
    }
}
