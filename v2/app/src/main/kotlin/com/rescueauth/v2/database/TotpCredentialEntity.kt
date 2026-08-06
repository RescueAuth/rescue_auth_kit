package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "totp_credential",
    foreignKeys = [
        ForeignKey(
            entity = AuthAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("accountId"), Index("legacySourceId")],
)
data class TotpCredentialEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val secretBase32: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val createdAt: String,
    val legacySourceId: String? = null,
)
