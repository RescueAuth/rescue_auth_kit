package com.rescueauth.v2.legacy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses legacy payload schema versions 1/2/3 into a uniform
 * [LegacyImportBundle]. Field shapes mirror `lib/core/vault/vault_models.dart`
 * at tag `v1.2.0` (schema 1 = d50d806, schema 2 = 922ddcb, schema 3 = HEAD).
 */
object LegacyPayloadParser {

    class ParseException(message: String) : Exception(message)

    fun parse(payloadJson: String): LegacyImportBundle {
        val root: JsonObject = try {
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(payloadJson).jsonObject
        } catch (e: Exception) {
            throw ParseException("Payload is not valid JSON: ${e.message}")
        }

        val schema = root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw ParseException("Missing schemaVersion")

        return when (schema) {
            1 -> parseSchema1(root)
            2 -> parseSchema2(root)
            3 -> parseSchema3(root)
            else -> throw ParseException("Unsupported legacy schemaVersion: $schema")
        }
    }

    // ------------------------------------------------------------------
    // Schema 1
    // ------------------------------------------------------------------

    private fun parseSchema1(root: JsonObject): LegacyImportBundle {
        val totp = (root["totpEntries"] as? JsonArray)?.mapNotNull { it -> parseTotp(it) }
            ?: emptyList()
        val recovery = (root["recoveryCodeSets"] as? JsonArray)
            ?.mapNotNull { parseRecoverySet(it) } ?: emptyList()
        return LegacyImportBundle(
            schemaVersion = 1,
            totpEntries = totp,
            recoveryCodeSets = recovery,
            developerEntries = emptyList(),
        )
    }

    // ------------------------------------------------------------------
    // Schema 2
    // ------------------------------------------------------------------

    private fun parseSchema2(root: JsonObject): LegacyImportBundle {
        val schema1 = parseSchema1(root)
        val developer = (root["developerEntries"] as? JsonArray)
            ?.mapNotNull { parseDeveloperEntry(it) } ?: emptyList()
        val developerSettings = (root["developerSettings"] as? JsonObject)
            ?.get("enabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return LegacyImportBundle(
            schemaVersion = 2,
            totpEntries = schema1.totpEntries,
            recoveryCodeSets = schema1.recoveryCodeSets,
            developerEntries = developer,
            developerSettings = developerSettings,
        )
    }

    // ------------------------------------------------------------------
    // Schema 3 (account-centric)
    // ------------------------------------------------------------------

    private fun parseSchema3(root: JsonObject): LegacyImportBundle {
        val providers = (root["providers"] as? JsonArray)
            ?.mapNotNull { it -> it as? JsonObject } ?: emptyList()
        val providerNameById = providers.associate { p ->
            (p["id"]?.jsonPrimitive?.content ?: "") to
                (p["name"]?.jsonPrimitive?.content ?: "")
        }
        val accounts = (root["accounts"] as? JsonArray)
            ?.mapNotNull { it -> it as? JsonObject } ?: emptyList()

        val totp = mutableListOf<LegacyTotpEntry>()
        val recovery = mutableListOf<LegacyRecoveryCodeSet>()

        accounts.forEachIndexed { index, account ->
            val accountId = account["id"]?.jsonPrimitive?.content ?: "acc-$index"
            val providerId = account["providerId"]?.jsonPrimitive?.content ?: ""
            val displayName = account["displayName"]?.jsonPrimitive?.content ?: ""
            val serviceName = providerNameById[providerId]?.takeIf { it.isNotEmpty() }
                ?: displayName.ifEmpty { "Untitled" }
            val createdAt = account["createdAt"]?.jsonPrimitive?.content

            (account["credentials"] as? JsonArray)?.forEach { credJson ->
                val cred = credJson as? JsonObject ?: return@forEach
                when (cred["kind"]?.jsonPrimitive?.content) {
                    "totp" -> totp.add(
                        LegacyTotpEntry(
                            id = cred["id"]?.jsonPrimitive?.content ?: "",
                            issuer = serviceName,
                            accountName = displayName,
                            secretBase32 = cred["secretBase32"]?.jsonPrimitive?.content ?: "",
                            algorithm = cred["algorithm"]?.jsonPrimitive?.content ?: "SHA1",
                            digits = cred["digits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6,
                            period = cred["period"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30,
                            createdAt = cred["createdAt"]?.jsonPrimitive?.content ?: createdAt,
                            legacySourceId = cred["id"]?.jsonPrimitive?.content,
                        )
                    )

                    "recoveryCodes" -> recovery.add(
                        LegacyRecoveryCodeSet(
                            id = cred["id"]?.jsonPrimitive?.content ?: "",
                            title = serviceName,
                            codes = (cred["codes"] as? JsonArray)
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull() }
                                ?: emptyList(),
                            createdAt = cred["createdAt"]?.jsonPrimitive?.content ?: createdAt,
                            legacySourceId = cred["id"]?.jsonPrimitive?.content,
                        )
                    )
                }
            }
        }

        val developer = (root["developerEntries"] as? JsonArray)
            ?.mapNotNull { parseDeveloperEntry(it) } ?: emptyList()
        val developerSettings = (root["developerSettings"] as? JsonObject)
            ?.get("enabled")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

        return LegacyImportBundle(
            schemaVersion = 3,
            totpEntries = totp,
            recoveryCodeSets = recovery,
            developerEntries = developer,
            developerSettings = developerSettings,
        )
    }

    // ------------------------------------------------------------------
    // Shared item parsers
    // ------------------------------------------------------------------

    private fun parseTotp(element: JsonElement): LegacyTotpEntry? {
        val o = element as? JsonObject ?: return null
        val secret = o["secretBase32"]?.jsonPrimitive?.contentOrNull() ?: return null
        return LegacyTotpEntry(
            id = o["id"]?.jsonPrimitive?.content ?: "",
            issuer = o["issuer"]?.jsonPrimitive?.content ?: "",
            accountName = o["accountName"]?.jsonPrimitive?.content ?: "",
            secretBase32 = secret,
            algorithm = o["algorithm"]?.jsonPrimitive?.content ?: "SHA1",
            digits = o["digits"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6,
            period = o["period"]?.jsonPrimitive?.content?.toIntOrNull() ?: 30,
            createdAt = o["createdAt"]?.jsonPrimitive?.content,
            legacySourceId = o["id"]?.jsonPrimitive?.content,
        )
    }

    private fun parseRecoverySet(element: JsonElement): LegacyRecoveryCodeSet? {
        val o = element as? JsonObject ?: return null
        return LegacyRecoveryCodeSet(
            id = o["id"]?.jsonPrimitive?.content ?: "",
            title = o["title"]?.jsonPrimitive?.content ?: "",
            codes = (o["codes"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull() } ?: emptyList(),
            createdAt = o["createdAt"]?.jsonPrimitive?.content,
            legacySourceId = o["id"]?.jsonPrimitive?.content,
        )
    }

    private fun parseDeveloperEntry(element: JsonElement): LegacyDeveloperEntry? {
        val o = element as? JsonObject ?: return null
        return LegacyDeveloperEntry(
            id = o["id"]?.jsonPrimitive?.content ?: "",
            type = o["type"]?.jsonPrimitive?.content ?: "genericSecret",
            title = o["title"]?.jsonPrimitive?.content ?: "",
            notes = o["notes"]?.jsonPrimitive?.content ?: "",
            createdAt = o["createdAt"]?.jsonPrimitive?.content,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.content,
            payload = o["payload"] as? JsonObject,
        )
    }

    private fun JsonElement.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonPrimitive) content else null
}
