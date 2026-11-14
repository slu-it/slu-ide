package dev.sluide.barcodes.ui

import dev.sluide.barcodes.payload.Contact
import dev.sluide.barcodes.payload.Payloads
import dev.sluide.barcodes.payload.Wifi
import dev.sluide.barcodes.payload.WifiSecurity

/** The values of the Wi-Fi template dialog (ADR 0174), without JavaFX: the dialog edits them and asks [canInsert]. */
internal class WifiForm(
    initial: Wifi? = null,
) {
    var ssid: String = initial?.ssid.orEmpty()
    var security: WifiSecurity = initial?.security ?: WifiSecurity.WPA
    var password: String = initial?.password.orEmpty()
    var hidden: Boolean = initial?.hidden ?: false

    /** An open network has no password. */
    val passwordEnabled: Boolean get() = security != WifiSecurity.NONE

    val canInsert: Boolean get() = ssid.isNotEmpty()

    fun text(): String = Wifi(ssid, security, password, hidden).text()

    companion object {
        /** A form prefilled from [text] when that is a Wi-Fi text, else an empty one. */
        fun of(text: String) = WifiForm(text.takeIf { it.length <= Payloads.MAX_PARSED_LENGTH }?.let(Wifi::parse))
    }
}

/** The values of the Contact template dialog (ADR 0174), without JavaFX. */
internal class ContactForm(
    initial: Contact? = null,
) {
    var name: String = initial?.name.orEmpty()
    var organization: String = initial?.organization.orEmpty()
    var phone: String = initial?.phone.orEmpty()
    var email: String = initial?.email.orEmpty()
    var website: String = initial?.website.orEmpty()

    val canInsert: Boolean get() = listOf(name, organization, phone, email, website).any { it.isNotBlank() }

    fun text(): String = Contact(name, organization, phone, email, website).text()

    companion object {
        /** A form prefilled from [text] when that is a vCard or MECARD text, else an empty one. */
        fun of(text: String) = ContactForm(text.takeIf { it.length <= Payloads.MAX_PARSED_LENGTH }?.let(Contact::parse))
    }
}
