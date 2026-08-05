import com.github.jk1.license.render.JsonReportRenderer

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.license.report)
}

licenseReport {
    // "debugRuntimeClasspath" became ambiguous once the gms/foss flavor dimension was added
    // (silently resolved to an empty configuration instead of failing the build, collapsing
    // this report to nothing). Pin to the gms variant — the fuller of the two dependency
    // sets, and the one actually shipped via GitHub Releases/Orion Store/IzzyOnDroid.
    configurations = arrayOf("gmsDebugRuntimeClasspath")
    renderers = arrayOf(JsonReportRenderer("license-report.json"))
}

tasks.register("createAssetsFolder") {
    doLast {
        file("${project.projectDir}/src/main/assets").mkdirs()
    }
}

tasks.register<Copy>("copyLicenseReportToAssets") {
    dependsOn("generateLicenseReport", "createAssetsFolder")
    from(layout.buildDirectory.dir("reports/dependency-license").map { it.file("license-report.json") })
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.preBuild {
    dependsOn("copyLicenseReportToAssets")
}

android {
    namespace = "dev.yuwixx.resonance"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.yuwixx.resonance"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.2.0"
    }

    // "gms" is the full build (Chromecast + Nearby Share via Google Play Services) shipped to
    // GitHub Releases / Orion Store / IzzyOnDroid. "foss" strips both — required for official
    // F-Droid inclusion, which rejects hard dependencies on GMS. Cast/Nearby call sites never
    // reference GMS types directly (they go through CastManager/NearbyShareManager, or the
    // CastButton composable), so each flavor just provides its own implementation of those
    // under identical public APIs — no call-site branching needed anywhere else.
    flavorDimensions += "distribution"
    productFlavors {
        create("gms") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.compose.foundation.layout)
    // ── Compose BOM ──────────────────────────────────────────────────────────
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Navigation ───────────────────────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ── Hilt ─────────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ── Room ─────────────────────────────────────────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ── Media3 (ExoPlayer) ───────────────────────────────────────────────────
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-common:1.5.0")

    // ── Google Cast (gms flavor only — pulls in androidx.mediarouter transitively) ──────────
    "gmsImplementation"(libs.play.services.cast.framework)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ── WorkManager ──────────────────────────────────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // ── DataStore ────────────────────────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Glance (home screen widget) ──────────────────────────────────────────
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // ── AppCompat (required for MediaRouteChooserDialog / AppCompatDialog) ──────
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── Splash Screen ────────────────────────────────────────────────────────
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ── Palette ──────────────────────────────────────────────────────────────
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ── Lifecycle ────────────────────────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ── Networking ───────────────────────────────────────────────────────────
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    // ── Coil (image loading) ─────────────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ── Accompanist (permissions) ────────────────────────────────────────────
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // ── Coroutines ───────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Guava (used by Media3 internals) ─────────────────────────────────────
    implementation("com.google.guava:guava:33.3.1-android")
    // ── Share (Nearby Connections is gms flavor only; QR/relay sharing works on foss too) ────
    "gmsImplementation"("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // ── Tag Editing ──────────────────────────────────────────────────────────
    implementation("net.jthink:jaudiotagger:3.0.1")
    // ── Secure Storage (Keystore-backed encryption for Navidrome password / Last.fm session key) ──
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── Testing ──────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.14.11")
}

// Robolectric's SQLite is backed by a real native library, which resolves 'localtime' in
// HistoryDao's date/hour queries via the process's TZ environment variable (glibc tzset()) —
// java.util.TimeZone.setDefault() alone doesn't reach it. Pinning TZ for the whole test JVM
// process keeps those date-bucketing tests deterministic regardless of the host/CI timezone.
tasks.withType<Test> {
    environment("TZ", "UTC")
}
