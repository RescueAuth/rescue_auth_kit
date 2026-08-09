package com.rescueauth.v2.repository

import com.rescueauth.v2.database.AuthAccountEntity
import com.rescueauth.v2.database.RecoveryCodeEntity
import com.rescueauth.v2.database.RecoveryCodeSetEntity
import com.rescueauth.v2.database.TotpCredentialEntity
import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.RecoveryCode
import com.rescueauth.v2.domain.RecoveryCodeSet
import com.rescueauth.v2.domain.TotpCredential

/** Internal mapper between Room entities and the domain layer. */
internal object AuthMappers {

    fun toDomain(e: AuthAccountEntity): AuthAccount = AuthAccount(
        id = e.id,
        stableId = e.stableId,
        serviceName = e.serviceName,
        accountName = e.accountName,
        sortOrder = e.sortOrder,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        favorite = e.favorite,
        notes = e.notes,
    )

    fun toDomain(e: TotpCredentialEntity): TotpCredential = TotpCredential(
        id = e.id,
        stableId = e.stableId,
        accountId = e.accountId,
        secretBase32 = e.secretBase32,
        algorithm = e.algorithm,
        digits = e.digits,
        periodSeconds = e.periodSeconds,
        createdAt = e.createdAt,
    )

    fun toDomain(e: RecoveryCodeSetEntity, codes: List<RecoveryCodeEntity>): RecoveryCodeSet =
        RecoveryCodeSet(
            id = e.id,
            stableId = e.stableId,
            accountId = e.accountId,
            title = e.title,
            createdAt = e.createdAt,
            codes = codes.map { toDomain(it) },
        )

    fun toDomain(e: RecoveryCodeEntity): RecoveryCode = RecoveryCode(
        id = e.id,
        stableId = e.stableId,
        setId = e.setId,
        value = e.value,
        isUsed = e.status == "USED",
        usedAt = e.usedAt,
        sortOrder = e.sortOrder,
    )
}
