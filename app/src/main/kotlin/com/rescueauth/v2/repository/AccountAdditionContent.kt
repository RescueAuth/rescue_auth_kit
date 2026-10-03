package com.rescueauth.v2.repository

/** A manual add draft, consumed only by a serialized repository mutation. Never logged or saved as UI state. */
sealed interface AccountAdditionContent {
    data object Empty : AccountAdditionContent
    data class Totp(val secret: String, val algorithm: String, val digits: Int, val periodSeconds: Int) : AccountAdditionContent
    data class Recovery(val values: List<String>, val title: String = "", val defaultTitle: String) : AccountAdditionContent
}
