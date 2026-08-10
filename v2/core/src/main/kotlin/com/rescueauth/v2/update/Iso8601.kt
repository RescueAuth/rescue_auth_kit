package com.rescueauth.v2.update

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * ISO-8601 validation for the manifest `publishedAt` field.
 *
 * Accepts any ISO-8601 offset date-time that `java.time` can parse (e.g.
 * `2026-08-07T12:34:56Z` or `2026-08-07T12:34:56+08:00`). A strictly valid
 * offset date-time is required; bare dates or malformed strings are rejected.
 */
object Iso8601 {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun isValid(value: String): Boolean {
        if (value.isBlank()) return false
        return try {
            OffsetDateTime.parse(value, formatter)
            true
        } catch (_: DateTimeParseException) {
            false
        }
    }
}
