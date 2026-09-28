package com.rescueauth.v2.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R

/** A visible per-action authentication hint; expanding it never starts or grants authentication. */
@Composable
fun ProtectedFieldsCard(entryId: String, content: @Composable () -> Unit) {
    RescueAuthExplainedCard(
        identityKey = entryId,
        title = stringResource(R.string.developer_protection_hint),
        explanationTitle = stringResource(R.string.developer_protection_title),
        explanation = stringResource(R.string.developer_protection_explanation),
        icon = Icons.Outlined.VerifiedUser,
        testTagPrefix = "developer_protection",
        compact = true,
        closeLabel = stringResource(R.string.developer_protection_dismiss),
        content = content,
    )
}
