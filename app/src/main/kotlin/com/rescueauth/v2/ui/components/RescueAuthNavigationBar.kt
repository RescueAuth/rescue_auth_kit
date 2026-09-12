package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.navigation.TopLevelDestination
import com.rescueauth.v2.ui.navigation.TopLevelDestinations

/** Flat, separated navigation chrome shared by the live shell and previews. */
@Composable
fun RescueAuthNavigationBar(
    isSelected: (TopLevelDestination) -> Boolean,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    Column {
        RescueAuthDivider()
        NavigationBar(
            modifier = Modifier.height(76.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            TopLevelDestinations.all.forEach { destination ->
                NavigationBarItem(
                    selected = isSelected(destination),
                    onClick = { onNavigate(destination) },
                    icon = { Icon(destination.icon, null, Modifier.size(22.dp)) },
                    label = {
                        Text(stringResource(destination.labelRes), style = MaterialTheme.typography.labelSmall)
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.testTag(destination.testTag),
                )
            }
        }
    }
}
