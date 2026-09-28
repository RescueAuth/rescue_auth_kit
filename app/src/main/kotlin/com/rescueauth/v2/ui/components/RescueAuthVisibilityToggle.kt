package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.ScreenTokens

/** Icon-only trailing action; visibility remains local to each input. */
@Composable
fun RescueAuthVisibilityToggle(revealed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(ScreenTokens.controlMinHeight)) {
        Icon(
            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(if (revealed) R.string.pin_hide else R.string.pin_show),
        )
    }
}
