package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.Build
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.R
import com.rescueauth.v2.export.*
import com.rescueauth.v2.exportimport.*
import com.rescueauth.v2.legacyimport.*
import com.rescueauth.v2.migration.*
import com.rescueauth.v2.ui.authenticator.*
import com.rescueauth.v2.ui.developer.*
import com.rescueauth.v2.ui.model.*
import com.rescueauth.v2.ui.screens.about.AboutScreen
import com.rescueauth.v2.ui.screens.authenticator.*
import com.rescueauth.v2.ui.screens.developer.*
import com.rescueauth.v2.ui.screens.exportimport.*
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportScreen
import com.rescueauth.v2.ui.screens.search.SearchScreen
import com.rescueauth.v2.ui.screens.startup.*
import com.rescueauth.v2.ui.search.SearchUiState
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.update.UpdateUiState
import com.rescueauth.v2.update.UpdateManifest
import com.rescueauth.v2.update.Severity
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Complements the main and secondary screenshot suites with all form families
 * and major sheets/states. Uses shipped Compose functions and safe metadata;
 * no secret values, database, auth bypass, file writes or network callbacks.
 * Camera captures use only the emulator's synthetic scene.
 */
@RunWith(AndroidJUnit4::class)
class CompleteVisualReviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var originalConfiguration: Configuration? = null

    @After @Suppress("DEPRECATION") fun restoreActivityLocale() {
        originalConfiguration?.let { original -> InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = rule.activity.resources
            resources.updateConfiguration(original, resources.displayMetrics)
        } }
    }

    private data class Scene(
        val id: String, val title: String, val section: String,
        val body: @Composable () -> Unit,
        val action: (() -> Unit)? = null,
    )

    @Suppress("DEPRECATION")
    private fun tour(scenes: List<Scene>, fontScale: Float = 1f, after: ((Scene) -> Unit)? = null) {
        val current = mutableStateOf<Scene?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = rule.activity.resources
            originalConfiguration = Configuration(resources.configuration)
            resources.updateConfiguration(Configuration(resources.configuration).apply {
                setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)); this.fontScale = fontScale
            }, resources.displayMetrics)
            rule.activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides rule.activity,
                LocalOnBackPressedDispatcherOwner provides rule.activity,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                RescueAuthTheme(darkTheme = false) {
                    Surface(Modifier.fillMaxSize()) {
                        current.value?.let { scene -> key(scene.id) { scene.body() } }
                    }
                }
            }
        }
        scenes.forEach { scene ->
            rule.runOnIdle { current.value = scene }
            rule.mainClock.advanceTimeBy(800)
            rule.waitForIdle()
            scene.action?.invoke()
            capture(scene)
            after?.invoke(scene)
        }
    }

    private fun capture(scene: Scene) {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(350, 5_000)
        // Compose idleness does not guarantee that Android has presented the latest window buffer.
        // Commit a real frame before reading the display; otherwise sequential scenes can lag a frame.
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                (WindowInspector.getGlobalWindowViews().lastOrNull { it.isAttachedToWindow && it.isShown }
                    ?: rule.activity.window.decorView).apply {
                    viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    invalidate()
                }
            }
            assertTrue("Latest review frame was not committed", committed.await(5, TimeUnit.SECONDS))
        }
        val directory = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "complete-ui-review").apply { mkdirs() }
        val image = checkNotNull(automation.takeScreenshot())
        try {
            File(directory, "${scene.id}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
        File(directory, "${scene.id}.json").writeText(JSONObject().put("id", scene.id)
            .put("title", scene.title).put("section", scene.section).put("source", "CompleteVisualReviewTest")
            .put("evidence", "native Compose, synthetic metadata, protected values empty").toString())
    }

    @Composable private fun accountBackground() {
        AuthenticatorScreen(uiState = AuthenticatorUiState(loading = false,
            providers = listOf(ProviderUi("demo", "GitHub", listOf(AccountUi("demo", "GitHub", "Personal account"))))),
            onAddClick = {}, showAddAction = false)
    }

    @Composable private fun addTotp(form: AddTotpFormState = AddTotpFormState(mode = AddMode.MANUAL)) {
        accountBackground()
        var draft by remember { mutableStateOf(AccountAddFormState(visible = true, scope = AccountAddScope.VAULT,
            kind = AccountAddContentKind.TOTP, totp = form)) }
        AccountAddSheet(draft, listOf(AccountUi("demo", "GitHub", "Personal account")), {}, {},
            onAccountNameChange = { draft = draft.copy(accountName = it) }, onKindChange = { draft = draft.copy(kind = it) },
            onTotpChange = { change -> draft = draft.copy(totp = change(draft.totp)) },
            onRecoveryTitleChange = {}, onRecoveryValuesChange = {}, onStartScan = {}, onSubmit = {},
            onProviderChange = { draft = draft.copy(provider = it) })
    }

    @Test fun accountFormsAndSheets() = tour(listOf(
        Scene("100-totp-methods", "首页添加 · 一级验证码", "账户", { addTotp() }),
        Scene("101-totp-paste", "添加验证码 · 粘贴", "账户", { addTotp() },
            { rule.onNodeWithTag("account_add_method_PASTE").performClick() }),
        Scene("102-totp-manual", "添加验证码 · 手动输入", "账户", { addTotp(AddTotpFormState(mode = AddMode.MANUAL)) }),
        Scene("103-totp-scan-choice", "添加验证码 · 二级高级参数", "账户", { addTotp() },
            { rule.onNodeWithTag("account_add_advanced").performScrollTo().performClick() }),
        Scene("104-provider-create", "创建服务与账户", "账户", { addTotp() },
            { rule.onNodeWithTag("account_add_kind_NONE").performClick() }),
        Scene("105-provider-rename", "重命名服务", "账户", { accountBackground(); ManagementTextDialog(
            stringResource(R.string.provider_rename_title), stringResource(R.string.provider_new_name_label), "GitHub",
            stringResource(R.string.management_confirm), {}, {}) }),
        Scene("106-account-create", "账户添加 · 复用表单", "账户", { accountBackground();
            AccountAddSheet(AccountAddFormState(visible = true, provider = "GitHub"), emptyList(), {}, {}, {}, {}, {}, {}, {}, {}, {}) }),
        Scene("107-account-rename", "重命名账户", "账户", { accountBackground(); ManagementTextDialog(
            stringResource(R.string.account_rename_title), stringResource(R.string.account_name_label), "Personal account",
            stringResource(R.string.management_confirm), {}, {}) }),
        Scene("108-account-move", "移动账户", "账户", { accountBackground(); ProviderPickerDialog(
            stringResource(R.string.account_move_title), listOf("GitHub", "Google"), "Google", {}, {}) }),
        Scene("109-account-merge", "合并账户", "账户", { accountBackground(); MergeAccountDialog(
            AccountUi("a", "GitHub", "Personal account"), AccountUi("b", "GitHub", "Workspace"), {}, {}) }),
        Scene("110-provider-delete", "删除服务 · 确认", "账户", { accountBackground(); ManagementDestructiveDialog(
            stringResource(R.string.provider_delete_title, "GitHub"), stringResource(R.string.provider_delete_message, 2, 2, 1),
            stringResource(R.string.provider_delete), {}, {}) }),
        Scene("111-provider-icon", "服务图标选择", "账户", { accountBackground(); ProviderIconPickerSheet(ProviderUi("demo", "GitHub"), {}, {}) }),
        Scene("112-recovery-add", "添加恢复码", "账户", { accountBackground(); RecoveryCodeEditorSheet(RecoveryFormState(), {}, {}, {}, {}) }),
        Scene("113-recovery-edit", "编辑恢复码", "账户", { accountBackground(); RecoveryCodeEditorSheet(
            RecoveryFormState(editingSetId = "demo", title = "GitHub recovery"), {}, {}, {}, {}) }),
        Scene("114-recovery-move", "移动恢复码", "账户", { accountBackground(); MoveRecoveryDialog("GitHub recovery",
            listOf(MoveDestination("demo", "GitHub", "Workspace")), {}, {}) }),
        Scene("115-migration-preview", "认证器迁移 · 预览", "账户", { accountBackground(); MigrationImportSheet(
            MigrationImportUiState(batchProgress = 1 to 1, candidates = listOf(MigrationTotpCandidate(
                MigrationEntryStatus.IMPORTABLE, name = "Personal account", issuer = "GitHub", algorithm = "SHA1", digits = 6, periodSeconds = 30))), {}, {}) }),
        Scene("116-accounts-empty", "账户 · 空状态", "账户", { AuthenticatorScreen(uiState = AuthenticatorUiState(loading = false), onAddClick = {}) }),
        Scene("117-search-empty", "搜索 · 初始状态", "账户", { SearchScreen(uiState = SearchUiState(unlocked = true)) }),
        Scene("118-search-no-results", "搜索 · 无结果", "账户", { SearchScreen(uiState = SearchUiState(query = "No matching item", unlocked = true)) }),
    ))

    @Composable private fun form(type: DeveloperFormType, editing: Boolean) {
        DeveloperFormScreen(
            form = DeveloperFormState(type = type, editingStableId = if (editing) "demo" else null,
                title = "Demo credential", serviceName = "GitHub", accountName = "Personal account", keyName = "Demo deploy key",
                projectName = "Demo project", packageName = "com.example.demo", keyAlias = "demo",
                fields = listOf("Username" to "", "Password" to ""), variables = listOf("API_ENDPOINT" to "", "ACCESS_TOKEN" to "")),
            onBack = {}, onTitleChange = {}, onNotesChange = {}, onServiceNameChange = {}, onAccountNameChange = {},
            onApiKeyChange = {}, onApiSecretChange = {}, onKeyNameChange = {}, onPublicKeyChange = {},
            onPrivateKeyChange = {}, onPassphraseChange = {}, onFieldLabelChange = { _, _ -> }, onFieldValueChange = { _, _ -> },
            onAddField = {}, onRemoveField = {}, onProjectNameChange = {}, onPackageNameChange = {}, onStorePasswordChange = {},
            onKeyAliasChange = {}, onKeyPasswordChange = {}, onKeystoreSelected = { _, _ -> }, onClearKeystore = {},
            onVariableNameChange = { _, _ -> }, onVariableValueChange = { _, _ -> }, onAddVariable = {}, onRemoveVariable = {}, onSubmit = {},
        )
    }

    private val formNames = mapOf(DeveloperFormType.API_CREDENTIAL to "API 凭据", DeveloperFormType.SSH_KEY to "SSH 密钥",
        DeveloperFormType.GENERIC_SECRET to "通用机密", DeveloperFormType.ANDROID_SIGNING_KEY to "签名密钥",
        DeveloperFormType.ENVIRONMENT_VARIABLE_SET to "环境变量集")

    @Test fun allDeveloperForms() {
        val scenes = listOf(false, true).flatMap { editing -> DeveloperFormType.entries.mapIndexed { index, type ->
            val verb = if (editing) "编辑" else "添加"
            Scene("${130 + index + if (editing) 10 else 0}-${type.name.lowercase()}", "$verb${formNames[type]} · 基本信息", "开发者",
                { form(type, editing) })
        } }
        tour(scenes) { scene ->
            rule.onNodeWithTag("developer_form_next").performClick()
            rule.onNodeWithTag("developer_form_save").assertIsDisplayed()
            if (scene.id.contains("generic_secret")) assertFieldAlignment("generic")
            if (scene.id.contains("environment_variable_set")) assertFieldAlignment("env")
            capture(scene.copy(id = scene.id + "-fields", title = scene.title.replace("基本信息", "凭据字段")))
            rule.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
            capture(scene.copy(id = scene.id + "-fields-end", title = scene.title.replace("基本信息", "字段底部")))
        }
    }

    private fun assertFieldAlignment(prefix: String) {
        for (index in 0..1) {
            fun slot(field: String) = rule.onNode(hasTestTag("input_slot") and
                hasAnyAncestor(hasTestTag("${prefix}_field_${field}_$index")), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            val name = slot("name")
            val value = slot("value")
            val remove = rule.onNodeWithTag("${prefix}_field_remove_$index").getUnclippedBoundsInRoot()
            val reveal = rule.onNode(hasTestTag("secret_visibility") and
                hasAnyAncestor(hasTestTag("${prefix}_field_value_$index")), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertEquals("Name and value must have the same width", (name.right-name.left).value, (value.right-value.left).value, .5f)
            assertEquals("Delete must align with the input, excluding its label", (name.top.value+name.bottom.value)/2,
                (remove.top.value+remove.bottom.value)/2, .5f)
            assertEquals("Delete and reveal must share the trailing column", (remove.left.value+remove.right.value)/2,
                (reveal.left.value+reveal.right.value)/2, .5f)
        }
    }

    @Test fun largeDynamicFieldsKeepAlignedActions() {
        tour(listOf(
            Scene("240-generic-large", "通用机密 · 大字体对齐", "开发者", { form(DeveloperFormType.GENERIC_SECRET, false) }),
            Scene("241-env-large", "环境变量 · 大字体对齐", "开发者", { form(DeveloperFormType.ENVIRONMENT_VARIABLE_SET, false) }),
        ), fontScale = 1.5f) { scene ->
            rule.onNodeWithTag("developer_form_next").performClick()
            assertFieldAlignment(if (scene.id.contains("generic")) "generic" else "env")
            capture(scene.copy(id = scene.id + "-fields", title = scene.title + " · 字段"))
        }
    }

    @Test fun developerCategoriesAndChooser() = tour(
        listOf(Scene("150-developer-add", "添加凭据 · 类型选择", "开发者", {
            DeveloperScreen(uiState = DeveloperListUiState(loading = false), onAddClick = {})
            DeveloperAddSheet({}, {})
        }), Scene("151-developer-empty", "开发者 · 空状态", "开发者", {
            DeveloperScreen(uiState = DeveloperListUiState(loading = false), onAddClick = {})
        })) + DeveloperEntryType.entries.mapIndexed { index, type ->
            Scene("${152 + index}-category", "${formNames[DeveloperFormType.valueOf(type.name)]} · 分类列表", "开发者", {
                DeveloperScreen(uiState = DeveloperListUiState(loading = false, entries = listOf(
                    DeveloperEntryUi("demo", "demo", type, "Demo project", "Personal workspace"))),
                    categoryType = type, onBack = {}, onAddClick = {}, onEntryClick = {})
            })
        }
    )

    private val selectable = SelectableItems(providers = listOf(SelectableProvider("GitHub", listOf(
        SelectableAccount("demo-account", "GitHub", "Personal account", listOf(SelectableItem("demo-totp", "GitHub")),
            listOf(SelectableRecoverySet("demo-recovery", "GitHub recovery", 4, 1)))))),
        developerEntries = listOf(SelectableDeveloperEntry("demo-api", "api_credential", "Demo project", "GitHub")))
    private val preview = ImportPreview("demo-package", "2026-09-27T00:00:00Z", "Android", "1.0.0", SnapshotScope.FULL_VAULT,
        2, 2, 1, 4, DeveloperPreviewSummary.from(emptyList()), 5, 0, 0, 0, 0)
    private val legacyPreview = LegacyImportPreview(1, "demo-source", 2, 2, 1, 4,
        LegacyDeveloperPreviewSummary(0, 0, 0, 0, 0, emptyList()), 5, 0, 0, 0, 0)

    @Composable private fun export(state: ExportImportViewModel.ExportState) = ExportVaultScreen(state,
        {}, {}, { _, _ -> null }, {}, {}, {}, onBack = {})
    @Composable private fun nativeImport(state: ExportImportViewModel.ImportState) = ImportNativePackageScreen(state,
        {}, {}, {}, {}, {}, {}, {}, {}, onBack = {})
    @Composable private fun legacy(state: LegacyImportViewModel.State) = LegacyImportScreen(state,
        {}, { false }, {}, {}, {}, {}, {}, onBack = {})

    @Test fun transferSteps() = tour(listOf(
        Scene("170-export-selection", "导出 · 选择条目", "备份与设置", { export(ExportImportViewModel.ExportState.SelectingItems(selectable)) }),
        Scene("171-export-reauth", "导出 · 等待身份验证", "备份与设置", { export(ExportImportViewModel.ExportState.AwaitingReauth) }),
        Scene("172-export-pin", "导出 · 设置本次 PIN", "备份与设置", { export(ExportImportViewModel.ExportState.AwaitingPin) }),
        Scene("173-export-destination", "导出 · 保存位置", "备份与设置", { export(ExportImportViewModel.ExportState.AwaitingDestination) }),
        Scene("174-export-success", "导出 · 完成", "备份与设置", { export(ExportImportViewModel.ExportState.Success("demo-package", "完整保险库")) }),
        Scene("175-export-error", "导出 · 错误提示", "备份与设置", { export(ExportImportViewModel.ExportState.Error("未能写入所选位置，请重试。")) }),
        Scene("176-import-pin", "导入 · 输入 PIN", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.AwaitingPin) }),
        Scene("177-import-scope", "导入 · 选择范围", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.ChoosingScope(preview)) }),
        Scene("178-import-selection", "导入 · 选择条目", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.SelectingItems(selectable)) }),
        Scene("179-import-preview", "导入 · 合并预览", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.Preview(preview)) }),
        Scene("180-import-result", "导入 · 完成", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.Result(5, 0, 0, 0, 0, false)) }),
        Scene("181-import-error", "导入 · 错误提示", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.Error("无法解密数据包，请检查 PIN 或文件。")) }),
        Scene("182-legacy-file", "旧版迁移 · 文件信息", "备份与设置", { legacy(LegacyImportViewModel.State.FileSelected("demo.rakvault", 4096)) }),
        Scene("183-legacy-password", "旧版迁移 · 密码输入", "备份与设置", { legacy(LegacyImportViewModel.State.AwaitingPassword) }),
        Scene("184-legacy-preview", "旧版迁移 · 合并预览", "备份与设置", { legacy(LegacyImportViewModel.State.Preview(legacyPreview)) }),
        Scene("185-legacy-result", "旧版迁移 · 完成", "备份与设置", { legacy(LegacyImportViewModel.State.Success(5, 0, 0, 0, 0, false)) }),
        Scene("186-legacy-error", "旧版迁移 · 错误提示", "备份与设置", { legacy(LegacyImportViewModel.State.Error("无法解密文件，请检查密码。", LegacyImportViewModel.ErrorAction.RETRY_PASSWORD)) }),
    ))

    @Test fun startupAndAboutStates() = tour(listOf(
        Scene("190-startup-auth", "启动 · 认证背景", "启动与外观", { StartupAuthHost() }),
        Scene("191-startup-opening", "启动 · 正在打开", "启动与外观", { StartupOpeningHost() }),
        Scene("192-startup-no-device-lock", "启动 · 未设置设备锁", "启动与外观", { NoSecureDeviceScreen({}) }),
        Scene("193-about-current", "关于 · 已是最新", "备份与设置", { AboutScreen("1.0.0", 10000,
            UpdateUiState.UpToDate(UpdateUiState.AppVersion("1.0.0", 10000)), {}, {}, onBack = {}) }),
        Scene("194-about-error", "关于 · 更新未配置", "备份与设置", { AboutScreen("1.0.0", 10000,
            UpdateUiState.Error(UpdateUiState.ErrorType.NOT_CONFIGURED), {}, {}, onBack = {}) }),
    ))

    private fun assertSheetActionRatio(primaryLabel: String) {
        val secondary = rule.onNodeWithText("取消").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithText(primaryLabel).getUnclippedBoundsInRoot()
        assertEquals((primary.right - primary.left).value * 2f / 3f,
            (secondary.right - secondary.left).value, 1f)
    }

    @Test fun recoveryFooterKeepsTwoToThreeRatio() = tour(listOf(
        Scene("320-recovery-actions", "恢复码 · 双按钮比例", "账户", {
            RecoveryCodeEditorSheet(RecoveryFormState(), {}, {}, {}, {})
        }, { assertSheetActionRatio("保存") }),
    ))

    @Test fun migrationFooterKeepsTwoToThreeRatio() = tour(listOf(
        Scene("321-migration-actions", "认证器迁移 · 双按钮比例", "账户", {
            MigrationImportSheet(MigrationImportUiState(batchProgress = 1 to 1,
                candidates = listOf(MigrationTotpCandidate(MigrationEntryStatus.IMPORTABLE,
                    name = "Review account", issuer = "Review service", algorithm = "SHA1", digits = 6, periodSeconds = 30))), {}, {})
        }, { assertSheetActionRatio("导入 1") }),
    ))

    @Test fun migrationLargeTextReview() = tour(listOf(
        Scene("322-migration-large", "认证器迁移 · 大字体", "账户", {
            MigrationImportSheet(MigrationImportUiState(batchProgress = 2 to 2,
                candidates = (1..8).map { MigrationTotpCandidate(MigrationEntryStatus.IMPORTABLE,
                    name = "Review account $it", issuer = "Review service", algorithm = "SHA1", digits = 6, periodSeconds = 30) }), {}, {})
        }),
    ), fontScale = 1.5f)

    @Test fun supplementaryTransferStates() = tour(listOf(
        Scene("300-export-working", "导出 · 处理中", "备份与设置", { export(ExportImportViewModel.ExportState.Working) }),
        Scene("301-import-decoding", "导入 · 解密中", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.Decoding) }),
        Scene("302-import-applying", "导入 · 写入中", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.Applying) }),
        Scene("303-import-conflict", "导入 · 冲突预览", "备份与设置", { nativeImport(ExportImportViewModel.ImportState.Preview(preview.copy(conflicts = 2))) }),
        Scene("304-legacy-decrypting", "旧版迁移 · 解密中", "备份与设置", { legacy(LegacyImportViewModel.State.Decrypting) }),
        Scene("305-legacy-applying", "旧版迁移 · 写入中", "备份与设置", { legacy(LegacyImportViewModel.State.Applying) }),
        Scene("306-legacy-blocked", "旧版迁移 · 冲突", "备份与设置", { legacy(LegacyImportViewModel.State.Error("存在冲突，当前内容未被覆盖。", LegacyImportViewModel.ErrorAction.BLOCKED)) }),
    ))

    @Test fun updateStatusVariants() {
        val latest = UpdateManifest(1, "stable", "1.1.0", 10100, 10000,
            "2026-09-29T08:00:00Z", "https://example.com/review.apk", 1, "0".repeat(64),
            "https://example.com/review", Severity.NORMAL)
        tour(listOf(
            Scene("310-update-available", "关于 · 新版本", "备份与设置", { AboutScreen("1.0.0", 10000,
                UpdateUiState.UpdateAvailable(UpdateUiState.AppVersion("1.0.0", 10000), latest, Severity.NORMAL, false), {}, {}, {}) },
                { rule.onNodeWithTag("about_open_release_page").performScrollTo() }),
            Scene("311-update-security", "关于 · 安全更新", "备份与设置", { AboutScreen("1.0.0", 10000,
                UpdateUiState.UpdateAvailable(UpdateUiState.AppVersion("1.0.0", 10000), latest.copy(severity = Severity.SECURITY), Severity.SECURITY, true), {}, {}, {}) },
                { rule.onNodeWithTag("about_open_release_page").performScrollTo() }),
            Scene("312-update-network-error", "关于 · 网络错误", "备份与设置", { AboutScreen("1.0.0", 10000,
                UpdateUiState.Error(UpdateUiState.ErrorType.NETWORK), {}, {}, {}) }),
        ))
    }

    @Test fun qrScanner() = tour(listOf(Scene("195-qr-scanner", "二维码扫描", "账户", { QrScannerScreen({}, {}) })))
}
