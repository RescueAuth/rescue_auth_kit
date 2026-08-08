plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.compose)
}

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

        // Room schema export (migration/schema tests + review).
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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
