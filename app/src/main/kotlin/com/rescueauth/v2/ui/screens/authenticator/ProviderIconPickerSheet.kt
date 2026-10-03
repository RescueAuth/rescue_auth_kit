package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import com.rescueauth.v2.ui.components.RescueAuthSheet
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.BrandIcons
import com.rescueauth.v2.ui.components.RescueAuthAutoBadge
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Bottom sheet for choosing a Provider icon (schema v4 provider_meta).
 *
 * Grid layout: AUTO (brand auto-match / letter), LETTER (force the letter
 * badge), then every built-in brand glyph. The selection is persisted by the
 * caller through [onSelect] with `null` meaning AUTO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderIconPickerSheet(
    provider: ProviderUi,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    RescueAuthSheet(stringResource(R.string.provider_icon_picker_title), Icons.Filled.GridView, onDismiss) {
            Text(
                text = stringResource(R.string.provider_icon_picker_subtitle, provider.serviceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
            RescueAuthCard {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 72.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(bottom = Spacing.lg),
            ) {
                item(key = "auto") {
                    IconChoice(
                        label = stringResource(R.string.provider_icon_auto),
                        selected = provider.iconKey == null,
                        onClick = { onSelect(null) },
                    ) {
                        RescueAuthAutoBadge(
                            label = provider.serviceName,
                            colorSeed = provider.serviceName,
                            iconRes = BrandIcons.effectiveDrawableRes(null, provider.serviceName),
                        )
                    }
                }
                item(key = "letter") {
                    IconChoice(
                        label = stringResource(R.string.provider_icon_letter),
                        selected = provider.iconKey == com.rescueauth.v2.database.PROVIDER_ICON_LETTER,
                        onClick = { onSelect(com.rescueauth.v2.database.PROVIDER_ICON_LETTER) },
                    ) {
                        RescueAuthAutoBadge(
                            label = provider.serviceName,
                            colorSeed = provider.serviceName,
                        )
                    }
                }
                items(BrandIcons.all, key = { it.key }) { brand ->
                    IconChoice(
                        label = brand.label,
                        selected = provider.iconKey == brand.key,
                        onClick = { onSelect(brand.key) },
                    ) {
                        RescueAuthAutoBadge(
                            label = brand.label,
                            colorSeed = provider.serviceName,
                            iconRes = brand.drawableRes,
                        )
                    }
                }
            }
            }
    }
}

@Composable
private fun IconChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
        ) {
            Box(modifier = Modifier.padding(Spacing.xs)) {
                badge()
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}
