package com.switchon.customer.data

/** Field rules shared by the screens and the view model. Each returns null when the input is fine. */
object Validation {
    const val NOTES_MAX = 300
    const val NAME_MAX = 60

    fun mobile(digits: String): String? = when {
        digits.isEmpty() -> "Enter a mobile number for the pickup partner."
        digits.any { !it.isDigit() } -> "Use digits only."
        digits.length != 10 -> "Enter all 10 digits (${digits.length}/10)."
        digits.first() !in '6'..'9' -> "Indian mobile numbers start with 6, 7, 8 or 9."
        else -> null
    }

    fun name(name: String): String? = when {
        name.isBlank() -> "Add a name so the partner knows who to ask for."
        name.trim().length < 2 -> "Use at least 2 letters."
        name.length > NAME_MAX -> "Keep it under $NAME_MAX characters."
        else -> null
    }

    fun pincode(pin: String): String? = when {
        pin.length != 6 || pin.any { !it.isDigit() } -> "Pincodes have 6 digits."
        pin.first() == '0' -> "Indian pincodes don't start with 0."
        !Catalog.isServiceable(pin) -> "We don't pick up from $pin yet. We serve Bengaluru, Mumbai, Delhi NCR, Pune and Hyderabad."
        else -> null
    }

    fun required(value: String, what: String): String? = if (value.isBlank()) "$what is needed so the partner can find you." else null

    fun notes(notes: String): String? = if (notes.length > NOTES_MAX) "Keep notes under $NOTES_MAX characters." else null

    private val upiPattern = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,63}$")

    fun upiId(id: String): String? = when {
        id.isBlank() -> "Enter your UPI ID, like name@okhdfcbank."
        !upiPattern.matches(id.trim()) -> "That doesn't look like a UPI ID. It should be like name@bank."
        else -> null
    }
}
