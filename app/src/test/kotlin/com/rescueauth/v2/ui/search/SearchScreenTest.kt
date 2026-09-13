package com.rescueauth.v2.ui.search

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.search.SearchResult
import com.rescueauth.v2.ui.screens.search.SearchScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun filterKeepsEachResultReachableWithoutExpandingGroups() {
        val provider = SearchResult.Provider("Workspace", null, "workspace", accountCount = 1)
        val account = SearchResult.Account("Personal", "Workspace", "account", false, providerName = "Workspace")
        var clicked: SearchResult? = null
        rule.setContent {
            RescueAuthTheme {
                SearchScreen(uiState = SearchUiState(query = "work", results = listOf(provider, account)),
                    onResultClick = { clicked = it })
            }
        }
        rule.onNodeWithTag("search_filter_account").performClick()
        rule.onNodeWithTag("search_result_provider_workspace").assertDoesNotExist()
        rule.onNodeWithTag("search_result_account_account").performScrollTo().performClick()
        assertEquals(account, clicked)
        rule.onNodeWithTag("search_filter_all").performClick()
        rule.onNodeWithTag("search_result_provider_workspace").assertExists()
    }
}
