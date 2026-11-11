package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.PasswordRules
import dev.sluide.encryptedfiles.Strength

/**
 * What the password dialogs decide while typing, without JavaFX (ADR 0084): the strength hint of the new password, why
 * the confirming button is disabled, and whether it is enabled. The fields' texts are only read, never copied;
 * [requireCurrent] adds the current password of Change Password, which must not be empty nor equal the new one.
 */
internal class PasswordEntryModel(
    private val requireCurrent: Boolean = false,
) {
    private var current: CharSequence = ""
    private var new: CharSequence = ""
    private var repeat: CharSequence = ""

    /** Takes the fields' present texts. */
    fun update(
        new: CharSequence,
        repeat: CharSequence,
        current: CharSequence = "",
    ) {
        this.new = new
        this.repeat = repeat
        this.current = current
    }

    /** "Weak", "OK" or "Strong" for the new password; empty while it is empty. */
    val hint: String
        get() =
            if (new.isEmpty()) {
                ""
            } else {
                when (PasswordRules.strength(new)) {
                    Strength.WEAK -> "Weak"
                    Strength.OK -> "OK"
                    Strength.STRONG -> "Strong"
                }
            }

    /** Why the confirming button is disabled, or `null` when it is enabled. */
    val problem: String?
        get() =
            when {
                requireCurrent && current.isEmpty() -> ENTER_CURRENT
                !PasswordRules.acceptable(new) -> TOO_SHORT
                !new.contentEquals(repeat) -> DIFFER
                requireCurrent && new.contentEquals(current) -> SAME_AS_CURRENT
                else -> null
            }

    val valid: Boolean get() = problem == null

    companion object {
        const val TOO_SHORT = "At least 12 characters"
        const val DIFFER = "The passwords differ"
        const val ENTER_CURRENT = "Enter the current password"
        const val SAME_AS_CURRENT = "The new password is the same as the current one."
    }
}
