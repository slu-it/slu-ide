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

    /**
     * A different lower-case word of letters only with the hash code of [word] (3+ letters): the last three
     * characters are replaced by lower-case letters of the Latin blocks, so that code which splits text into letter
     * runs and lower-cases them still sees one word with that hash code.
     */
    fun ofLetters(word: String): String {
        require(word.length >= 3 && word.all { it in 'a'..'z' }) { "three or more letters a-z: $word" }
        val letters = (0x61..0x24F).filter { Character.isLowerCase(it) && Character.isLetter(it) }
        val tail = word.takeLast(3).map { it.code }
        val target = 961 * tail[0] + 31 * tail[1] + tail[2]
        for (a in letters) {
            for (b in letters) {
                val c = target - 961 * a - 31 * b
                if (c in letters && !(a == tail[0] && b == tail[1])) {
                    val other = word.dropLast(3) + a.toChar() + b.toChar() + c.toChar()
                    check(other.hashCode() == word.hashCode() && other != word && other.lowercase() == other)
                    return other
                }
            }
        }
        error("no letter collision for $word")
    }
}
