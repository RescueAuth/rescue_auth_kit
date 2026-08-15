import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// RescueAuth production Android signing configuration
// ---------------------------------------------------------------------------
// Release Provisioning Step 1 (docs/RELEASE_PROVISIONING.md).
//
// The production signing PRIVATE KEY must live entirely OUTSIDE the repo. It
// is loaded at Gradle configuration time from ONE of two sources (both
// optional, both must be complete to enable production signing):
//
//   1. a local `keystore.properties` file next to the Gradle project root
//      (the file is gitignored; see `keystore.properties.example`), or
//   2. environment variables / Gradle `-P` properties:
//        RESCUEAUTH_STORE_FILE / RESCUEAUTH_STORE_PASSWORD /
//        RESCUEAUTH_KEY_ALIAS / RESCUEAUTH_KEY_PASSWORD
//
// Rules enforced here:
//   * When ALL four fields resolve to non-blank values the `release` build
//     uses the new RescueAuth production signing identity.
//   * When ANY field is missing/blank, `release` builds UNSIGNED (never a
//     debug-signing fallback, never auto-generated keystore, never a silent
//     fake signature). Normal dev/CI can always build an unsigned release.
//   * `validateReleaseSigning` fails clearly with an explicit message when a
//     caller explicitly asks for a signed release but the config is
//     incomplete (no NPE / FileNotFound mystery / silent fallback).
//
// NO secret is ever printed, logged, or exposed via BuildConfig here.

val signingProps = Properties()
val localKeystorePropertiesFile = rootProject.file("keystore.properties")
if (localKeystorePropertiesFile.isFile) {
    signingProps.load(FileInputStream(localKeystorePropertiesFile))
}

fun resolveSecret(prop: String, envName: String): String? {
    // Prefer the local keystore.properties value, then env var / -P.
    val fromProps = signingProps.getProperty(prop)?.trim().orEmpty()
    if (fromProps.isNotEmpty()) return fromProps
    val fromEnv = providers.gradleProperty(envName).orNull
        ?: System.getenv(envName)
    return fromEnv?.trim()?.takeIf { it.isNotEmpty() }
}

val signingStoreFile = resolveSecret("storeFile", "RESCUEAUTH_STORE_FILE")
val signingStorePassword = resolveSecret("storePassword", "RESCUEAUTH_STORE_PASSWORD")
val signingKeyAlias = resolveSecret("keyAlias", "RESCUEAUTH_KEY_ALIAS")
val signingKeyPassword = resolveSecret("keyPassword", "RESCUEAUTH_KEY_PASSWORD")

val hasProductionSigningConfig = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all { !it.isNullOrBlank() }

// A signing storeFile path that does not exist is a hard error for a signed
// build, never a silent unsigned fallback.
val signingStorePath: File? = signingStoreFile?.let { File(it) }

// ---------------------------------------------------------------------------

android {
    namespace = "com.rescueauth.v2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rescueauth.v2"
        minSdk = 26
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phase 6 L2: Ed25519 update-manifest public key (Base64-encoded raw
        // 32-byte key). This is a RELEASE PROVISIONING boundary — not set by
        // default. When unset the update check returns NOT_CONFIGURED and the
        // Vault keeps working (fail open). The PRIVATE key is a CI secret only
        // and is never committed here.
        val updatePublicKey = (project.findProperty("UPDATE_PUBLIC_KEY") as? String)?.trim().orEmpty()
        buildConfigField(
            "String",
            "UPDATE_PUBLIC_KEY",
            "\"${updatePublicKey.replace("\"", "\\\"")}\"",
        )

        // Room schema export (migration/schema tests + review).
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (hasProductionSigningConfig) {
            create("release") {
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                storeFile = signingStorePath
                storePassword = signingStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only wire the production signing identity when the full config is
            // present. When it is absent the release build stays UNSIGNED — this
            // deliberately never falls back to debug signing.
            if (hasProductionSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/OSGI-INF/MANIFEST.MF",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
        jniLibs {
            useLegacyPackaging = false // 16 KB page-size aligned .so (AGP 8.7+ default)
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // Room MigrationTestHelper reads the exported schema JSONs from assets.
    // Register the exported schema dir as a DEBUG-only asset source so
    // Robolectric unit tests and instrumented tests can read them, while
    // release builds never ship the schema JSONs.
    sourceSets {
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// ---------------------------------------------------------------------------
// validateReleaseSigning
// ---------------------------------------------------------------------------
// Explicitly validates that a *signed* release is actually possible. This is
// for callers who intentionally want a production-signed build. When the
// production signing config is incomplete (or the keystore file is missing)
// this task FAILS with a clear message — never an NPE, never a silent
// fallback, never a silent debug signature.
//
// Normal dev/CI (`assembleRelease` without secrets) does NOT run this task and
// continues to produce an unsigned release, so the pipeline stays green.
//
// To enable signed release output on a production job, provide the four fields
// via a local `keystore.properties` or environment variables, then run:
//   ./gradlew :app:validateReleaseSigning :app:assembleRelease

tasks.register("validateReleaseSigning") {
    group = "release"
    description = "Fail clearly if a production-signed release cannot be built."
    doLast {
        val missing = buildList {
            if (signingStoreFile.isNullOrBlank()) add("storeFile")
            if (signingStorePassword.isNullOrBlank()) add("storePassword")
            if (signingKeyAlias.isNullOrBlank()) add("keyAlias")
            if (signingKeyPassword.isNullOrBlank()) add("keyPassword")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing RescueAuth production signing configuration: " +
                    missing.joinToString() +
                    ". Provide all four fields via keystore.properties " +
                    "(see keystore.properties.example) or environment variables " +
                    "RESCUEAUTH_STORE_FILE / RESCUEAUTH_STORE_PASSWORD / " +
                    "RESCUEAUTH_KEY_ALIAS / RESCUEAUTH_KEY_PASSWORD. " +
                    "See docs/RELEASE_PROVISIONING.md."
            )
        }
        if (signingStorePath == null || !signingStorePath.isFile) {
            throw GradleException(
                "RescueAuth production signing keystore not found: " +
                    (signingStorePath?.absolutePath ?: "<null>") +
                    ". See docs/RELEASE_PROVISIONING.md."
            )
        }
        logger.lifecycle(
            "RescueAuth production signing configuration is valid " +
                "(storeFile=${signingStorePath.absolutePath}, keyAlias=$signingKeyAlias)."
        )
    }
}

tasks.configureEach {
    if (name == "testDebugUnitTest") {
        // PageSize16KTest inspects the real packaged APK; the unit test task
        // must not run before the APK exists (the test fails loudly if it is
        // missing).
        dependsOn("assembleDebug")
        (this as Test).systemProperty(
            "rescueauth.debugApk",
            layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile.absolutePath,
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    // Local, non-sensitive UI preferences (theme color).
    implementation(libs.androidx.datastore.preferences)

    // Jetpack Compose UI foundation (BOM-managed)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX + ML Kit barcode scanning (Phase 4 P2 QR scanner).
    // Mature stack: CameraX lifecycle-aware camera + ML Kit on-device barcode
    // decoding. No network, no image upload, no analytics.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Room + SQLCipher (Zetetic current artifact)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)

    // Compose UI tests (Robolectric host + instrumented)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}

// KSP's bundled kotlinx-serialization 1.6.3 conflicts with Room 2.8.4's
// schema-JSON reader (compiled against 1.8.1) on the same KSP compile
// classpath. Force serialization to 1.8.1 everywhere so the runtime
// interface has the default `typeParametersSerializers()`.
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    }
}
