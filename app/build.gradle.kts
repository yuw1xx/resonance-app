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
    configurations = arrayOf("debugRuntimeClasspath")
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
        versionCode = 5
        versionName = "2.0.1"
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
}

dependencies {
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.play.services.cast.framework)
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

    // ── Google Cast ──────────────────────────────────────────────────────────
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
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
    // ── Share ────────────────────────────────────────────────────────────────
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.zxing:core:3.5.3")
    // ── Tag Editing ──────────────────────────────────────────────────────────
    implementation("net.jthink:jaudiotagger:3.0.1")
}
