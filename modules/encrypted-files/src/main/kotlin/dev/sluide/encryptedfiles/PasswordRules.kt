package dev.sluide.encryptedfiles

/** The hint shown under a new password. */
enum class Strength { WEAK, OK, STRONG }

/**
 * Rules for the password of an encrypted file (ADR 0162): at least [MIN_LENGTH] code points, so an emoji counts once.
 * Passwords are read from their arrays only; no String is made of them.
 */
object PasswordRules {
    const val MIN_LENGTH = 12

    fun acceptable(password: CharArray): Boolean = Character.codePointCount(password, 0, password.size) >= MIN_LENGTH

    /**
     * WEAK below [MIN_LENGTH] code points, or with one character class below 20; STRONG from 20 code points, or from
     * 14 with three of lowercase, uppercase, digits and others; OK otherwise.
     */
    fun strength(password: CharArray): Strength {
        var length = 0
        var lower = false
        var upper = false
        var digit = false
        var other = false
        var index = 0
        while (index < password.size) {
            val codePoint = Character.codePointAt(password, index)
            when {
                Character.isLowerCase(codePoint) -> lower = true
                Character.isUpperCase(codePoint) -> upper = true
                Character.isDigit(codePoint) -> digit = true
                else -> other = true
            }
            length++
            index += Character.charCount(codePoint)
        }
        val classes = listOf(lower, upper, digit, other).count { it }
        return when {
            length < MIN_LENGTH || (classes <= 1 && length < 20) -> Strength.WEAK
            length >= 20 || (length >= 14 && classes >= 3) -> Strength.STRONG
            else -> Strength.OK
        }
    }
}
