package dev.sluide.testsupport

/**
 * A different string with the hash code of [word]. Kotlin compiles a `when` over string constants to a switch on the
 * hash code followed by an equality check, so only such a string can tell whether that check is there.
 *
 * The last two characters change: the first by -d, the second by +31 d, which `hashCode` (31 * a + b) cannot tell.
 * d is 1 (c1 - 1, c2 + 31), else up to 4; a shift that would leave the range of a `Char` is skipped. A one-character
 * word gets a leading NUL. A candidate is accepted when [normalized] leaves it as it is: pass the normalization the
 * code under test applies before it compares (`{ it.trim().lowercase() }`), so that the colliding string still
 * reaches the `when`.
 *
 * @throws IllegalArgumentException if no candidate is in the range of a `Char` and unchanged by [normalized]
 */
fun collidingWith(
    word: String,
    normalized: (String) -> String = { it },
): String {
    val candidates =
        if (word.length == 1) {
            sequenceOf("\u0000$word")
        } else {
            (1..4).asSequence().mapNotNull { d -> shifted(word, d) }
        }
    return requireNotNull(candidates.firstOrNull { normalized(it) == it }) {
        "no string with the hash code of \"$word\" stays in the range of a Char and unchanged by the normalization"
    }
}

/** [word] with its last two characters moved by -[d] and +31 [d], or null if one leaves the range of a `Char`. */
private fun shifted(
    word: String,
    d: Int,
): String? {
    val first = word[word.length - 2].code - d
    val second = word.last().code + 31 * d
    if (first < 0 || second > Char.MAX_VALUE.code) return null
    return word.dropLast(2) + first.toChar() + second.toChar()
}

/**
 * A different lower-case word of letters only with the hash code of [word] (3+ letters a-z): the last three
 * characters are replaced by lower-case letters of the Latin blocks, so that code which splits text into letter
 * runs and lower-cases them still sees one word with that hash code.
 */
fun collidingLettersWith(word: String): String {
    require(word.length >= 3 && word.all { it in 'a'..'z' }) { "three or more letters a-z: $word" }
    val letters = (0x61..0x24F).filter { Character.isLowerCase(it) }
    val tail = word.takeLast(3).map { it.code }
    val target = 961 * tail[0] + 31 * tail[1] + tail[2]
    for (a in letters) {
        for (b in letters) {
            val c = target - 961 * a - 31 * b
            if (c in letters && !(a == tail[0] && b == tail[1])) {
                return word.dropLast(3) + a.toChar() + b.toChar() + c.toChar()
            }
        }
    }
    error("no letter collision for $word")
}
