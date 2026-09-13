package com.rescueauth.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.StudioVaultHero
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StudioBannerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shortAndTwoLineHeadlinesKeepTheSameCompactBannerHeight() {
        banners(fontScale = 1f)
        listOf("auth", "developer", "settings").forEach {
            rule.onNodeWithTag(it).assertHeightIsEqualTo(CardTokens.bannerHeight)
        }
    }

    @Test fun largerTextUsesOneSharedHeightAcrossEveryBanner() {
        banners(fontScale = 1.5f)
        listOf("auth", "developer", "settings").forEach {
            rule.onNodeWithTag(it).assertHeightIsEqualTo(CardTokens.bannerHeight + CardTokens.bannerLargeTextGrowth * 0.5f)
        }
    }

    private fun banners(fontScale: Float) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                RescueAuthTheme {
                    // Unbounded vertical measurement checks all three banners even on a short host screen.
                    androidx.compose.foundation.lazy.LazyColumn {
                        item {
                            Column(Modifier.width(360.dp)) {
                                StudioVaultHero("PERSONAL VAULT", "12 accounts", "6 services · 12 authenticators", Modifier.testTag("auth"))
                                StudioVaultHero("DEVELOPER VAULT", "Built for\nyour next idea.", "20 secure entries", Modifier.testTag("developer"))
                                StudioVaultHero("MAKE IT YOURS", "Yours.\nOn your terms.", "Encrypted by default.", Modifier.testTag("settings"))
                            }
                        }
                    }
                }
            }
        }
    }
}
