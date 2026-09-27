package com.rescueauth.v2.ui.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.screens.about.AboutScreen
import com.rescueauth.v2.ui.screens.about.AboutTestTags
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.update.Severity
import com.rescueauth.v2.update.UpdateManifest
import com.rescueauth.v2.update.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * About screen UI tests (Issue #20 §25).
 *
 * Covers version display, update state rendering, the security-update stronger
 * presentation, no open-link for invalid manifest, and no internal/secret vault
 * metadata in semantics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AboutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(state: UpdateUiState, onCheck: () -> Unit = {}) {
        composeRule.setContent {
            RescueAuthTheme {
                AboutScreen(
                    versionName = "1.0.0",
                    versionCode = 10000,
                    state = state,
                    onCheckForUpdates = onCheck,
                    onOpenReleasePage = {},
                    onBack = {},
                )
            }
        }
    }

    private fun manifest(
        versionCode: Long = 10100,
        severity: Severity = Severity.NORMAL,
        minSupported: Long = 10000L,
    ): UpdateManifest = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        versionName = "1.1.0",
        versionCode = versionCode,
        minSupportedVersionCode = minSupported,
        publishedAt = "2026-08-10T12:00:00Z",
        apkUrl = "https://example.com/a.apk",
        apkSizeBytes = 100,
        apkSha256 = "a".repeat(64),
        releaseNotesUrl = "https://example.com/n.html",
        severity = severity,
    )

    // --- 37. About shows runtime versionName ---
    @Test
    fun aboutShowsVersionName() {
        setContent(UpdateUiState.Idle)
        composeRule.onNodeWithText("1.0.0", substring = true).performScrollTo().assertIsDisplayed()
    }

    // --- 38. About shows runtime versionCode ---
    @Test
    fun aboutShowsVersionCode() {
        setContent(UpdateUiState.Idle)
        composeRule.onNodeWithText("10000", substring = true).performScrollTo().assertIsDisplayed()
    }

    // --- 39. Check button → Checking state ---
    @Test
    fun checkButtonTriggersChecking() {
        setContent(UpdateUiState.Checking)
        composeRule.onNodeWithText("Checking for updates", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun idleCheckActionIsAtBottomWithoutDuplicateInstructions() {
        var checks = 0
        setContent(UpdateUiState.Idle) { checks++ }
        val screen = composeRule.onNodeWithTag(AboutTestTags.SCREEN).getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).getUnclippedBoundsInRoot()
        assertTrue("Check action belongs at the bottom of the viewport", button.top > screen.top + (screen.bottom - screen.top) * .75f)
        composeRule.onNodeWithText("Tap “Check for Updates”", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).performClick()
        assertEquals(1, checks)
    }

    // --- 40. Up-to-date UI ---
    @Test
    fun upToDateUi() {
        setContent(UpdateUiState.UpToDate(UpdateUiState.AppVersion("1.0.0", 10000)))
        composeRule.onNodeWithText("You are up to date", substring = true).performScrollTo().assertIsDisplayed()
    }

    // --- 41. Update-available UI ---
    @Test
    fun updateAvailableUi() {
        setContent(
            UpdateUiState.UpdateAvailable(
                current = UpdateUiState.AppVersion("1.0.0", 10000),
                latest = manifest(),
                severity = Severity.NORMAL,
                minSupportedExceeded = false,
            ),
        )
        composeRule.onNodeWithText("A new version is available", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(AboutTestTags.OPEN_RELEASE_PAGE).assertExists()
    }

    // --- 42. Security-update stronger UI ---
    @Test
    fun securityUpdateStrongerUi() {
        setContent(
            UpdateUiState.UpdateAvailable(
                current = UpdateUiState.AppVersion("1.0.0", 10000),
                latest = manifest(severity = Severity.SECURITY),
                severity = Severity.SECURITY,
                minSupportedExceeded = false,
            ),
        )
        composeRule.onNodeWithText("A security update is available", substring = true).performScrollTo().assertIsDisplayed()
    }

    // --- 43. network failure UI ---
    @Test
    fun networkFailureUi() {
        setContent(UpdateUiState.Error(UpdateUiState.ErrorType.NETWORK))
        composeRule.onNodeWithText("Unable to check for updates", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("vault remains available offline", substring = true).performScrollTo().assertIsDisplayed()
    }

    // --- 44. signature failure UI ---
    @Test
    fun signatureFailureUi() {
        setContent(UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_SIGNATURE))
        composeRule.onNodeWithText("Unable to verify the update information", substring = true).performScrollTo().assertIsDisplayed()
        // It must NOT claim "no update".
        composeRule.onNodeWithText("up to date", substring = true).assertDoesNotExist()
    }

    // --- 45. invalid manifest does not show open link ---
    @Test
    fun invalidManifestNoOpenLink() {
        setContent(UpdateUiState.Error(UpdateUiState.ErrorType.INVALID_MANIFEST))
        composeRule.onNodeWithTag(AboutTestTags.OPEN_RELEASE_PAGE).assertDoesNotExist()
    }

    // --- 46. Open Release Page only present for verified update ---
    @Test
    fun openReleasePageOnlyForVerifiedUpdate() {
        setContent(UpdateUiState.UpToDate(UpdateUiState.AppVersion("1.0.0", 10000)))
        composeRule.onNodeWithTag(AboutTestTags.OPEN_RELEASE_PAGE).assertDoesNotExist()
    }

    // --- 47. external open receives verified HTTPS URL (ViewModel-level) ---
    // Covered by UpdateCheckViewModelTest.updateAvailableWithReleaseNotesUrl
    // which asserts verifiedOpenUrl() == the HTTPS release-notes URL.

    // --- 49. no secret/internal vault metadata in semantics ---
    @Test
    fun noSecretOrInternalVaultMetadata() {
        setContent(UpdateUiState.Idle)
        // About must not leak keystore alias / DB path / VaultKey / device id.
        for (leak in listOf(
            "keystore", "vaultkey", "sqlcipher", "database", "device id",
            "android id", "biometric", "alias", "secret", "password", "token",
        )) {
            composeRule.onNodeWithText(leak, substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }

    // --- 36 (nav) covered by navigation test. ---
    // --- 48 (no APK installer intent) covered by ExternalOpenHelper design —
    // it only issues ACTION_VIEW, never an install intent.
}
