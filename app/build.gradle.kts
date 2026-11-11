plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The site that serves the bank return page and assetlinks.json: ONE value (gradle.properties), used for the App Link filter and for the code.
val siteHost: String = providers.gradleProperty("pilastri.siteHost").get()

android {
    namespace = "xyz.grtlabs.pilastri"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "xyz.grtlabs.pilastri"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["siteHost"] = siteHost
        buildConfigField("String", "SITE_HOST", "\"$siteHost\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        // WebView DevTools stay on in debug and fast (connector device checks use them), off in release.
        getByName("debug") { buildConfigField("boolean", "WEBVIEW_DEVTOOLS", "true"); buildConfigField("String", "EB_BASE_URL", "\"http://10.0.2.2:8787\"") }
        getByName("release") { buildConfigField("boolean", "WEBVIEW_DEVTOOLS", "false"); buildConfigField("String", "EB_BASE_URL", "\"\"") }
        // "fast": the phone build. NOT debuggable (Compose performance must be judged on a non-debuggable build), but signed with the
        // debug key and the same applicationId, so `adb install -r` upgrades the existing debug install. R8/minify is OFF on purpose:
        // the connectors use WebView JS bridges that unit tests cannot exercise; R8 comes later as its own step with a device smoke test.
        create("fast") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "WEBVIEW_DEVTOOLS", "true")
            buildConfigField("String", "EB_BASE_URL", "\"\"")
            matchingFallbacks += listOf("release")
        }
    }
    // PdfBox-Android and the android.* stubs it touches run in JVM tests (see DocumentTestSupport): stubs return defaults.
    // isIncludeAndroidResources: Robolectric + Compose ui-test layout checks (ui/layout/LayoutConsistencyTest) need the merged resources and manifest.
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Applies the baseline profiles the Compose libraries ship, in non-debuggable builds.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.window:window:1.5.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    // Attachments (cedolini, CU): text layer of PDFs (Apache-2.0) and on-device OCR with the BUNDLED Latin model (no Play Services, no upload).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // JVM layout gate (AGENTS.md "The check is an algorithm"): Robolectric renders the app, Compose ui-test reads tagged bounds. Robolectric 4.16 runs up to SDK 36 (tests pin @Config(sdk = [36])).
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20250517")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

// Phase gate (AGENTS.md "Speed" / Phases): the main target is phase B (Galaxy Fold open + cover, font 1.0, light mode).
// The font-2.0 layout tests (PhoneFontScale2..., FoldFontScale2...) are phase C by the user's decision 2026-11-09; they are kept, not
// deleted. Recorded phase-C finding: the font-2.0 stat-row reflow ("1.84...", "Risparmi/ato", "Versato tuo · € gen"), wave 10 owner 6.
// testDebugUnitTest (and tools/install-fold.sh) skip them by default. Run them with:
//   bash tools/gw.sh :app:testDebugUnitTest -PphaseC --tests "*FontScale2*"
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("phaseC")) filter { excludeTestsMatching("*FontScale2*") }
}
