package com.rescueauth.v2.domain

/** Non-secret labels only. Call inside the serialized write when creating a set. */
internal fun nextRecoverySetTitle(baseTitle: String, existingTitles: Collection<String>): String {
    val base = baseTitle.trim().ifEmpty { "Recovery codes" }
    val existing = existingTitles.toHashSet()
    if (base !in existing) return base
    var number = 2
    while ("$base $number" in existing) number++
    return "$base $number"
}
