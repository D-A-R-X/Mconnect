plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

fun envOrEmpty(name: String): String = System.getenv(name) ?: ""
fun envOrDefault(name: String, defaultValue: String): String = System.getenv(name) ?: defaultValue
fun ensureScheme(url: String): String =
    if (url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) url
    else "https://$url"
fun ensureTrailingSlash(url: String): String {
    val withScheme = ensureScheme(url)
    return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
}
fun gradleProp(name: String): String = (project.findProperty(name) as String?) ?: ""

val googleMapsApiKey = envOrDefault(
    "GOOGLE_MAPS_ANDROID_KEY",
    envOrDefault(
        "CONVEX_GOOGLE_MAPS_ANDROID_KEY",
        envOrDefault(
            "GOOGLE_MAPS_API_KEY",
            envOrDefault(
                "NEXT_PUBLIC_GOOGLE_MAPS_WEB_KEY",
                envOrDefault(
                    "NEXT_PUBLIC_GOOGLE_MAPS_ANDROID_KEY",
                    gradleProp("GOOGLE_MAPS_ANDROID_KEY")
                )
            )
        )
    )
)
// Point the Android client at the same Convex deployment the web admin uses.
// Build-time overrides still apply (env NEXT_PUBLIC_CONVEX_SITE_URL or
// MCONNECT_BASE_URL), so a release pipeline can swap in the prod URL without
// touching this file.
val defaultBaseUrl = ensureTrailingSlash(
    envOrDefault("NEXT_PUBLIC_CONVEX_SITE_URL", "https://convex-http.aivida.in/")
)
val baseUrl = ensureTrailingSlash(
    envOrDefault("MCONNECT_BASE_URL", defaultBaseUrl)
)
val defaultAppUrl = ensureTrailingSlash(
    envOrDefault("NEXT_PUBLIC_APP_URL", "https://dev-convex-http.aivida.in/")
)
val appUrl = ensureTrailingSlash(
    envOrDefault("MCONNECT_APP_URL", defaultAppUrl)
)

// versionCode MUST strictly increase on every release, or Google Play — and the
// in-app update flow, which keys off it — won't offer the update. A hardcoded
// versionCode is what was blocking store updates. Derive it from the git commit
// count so it bumps automatically and can't be forgotten; a release pipeline may
// override via MCONNECT_VERSION_CODE. The floor (6) keeps us above the last store
// release (5) if git history is unavailable (e.g. a shallow CI clone — in that
// case set MCONNECT_VERSION_CODE explicitly).
fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    out.toIntOrNull() ?: 0
} catch (e: Exception) {
    0
}

val appVersionCode: Int =
    System.getenv("MCONNECT_VERSION_CODE")?.toIntOrNull() ?: maxOf(6, gitCommitCount())
val appVersionName: String =
    System.getenv("MCONNECT_VERSION_NAME") ?: "1.0.$appVersionCode"

android {
    namespace = "com.manjugroups.m_connect"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.manjugroups.mconnect"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"${baseUrl}\"")
        buildConfigField("String", "APP_URL", "\"${appUrl}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${envOrEmpty("FIREBASE_APPLICATION_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${envOrEmpty("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${envOrEmpty("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_GCM_SENDER_ID", "\"${envOrEmpty("FIREBASE_GCM_SENDER_ID")}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${envOrEmpty("FIREBASE_STORAGE_BUCKET")}\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${googleMapsApiKey}\"")
        manifestPlaceholders["googleMapsApiKey"] = googleMapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        jvmToolchain(11)
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.security.crypto)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.swiperefresh)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // GeoTrack dependencies
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.workmanager)
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.emoji2.emojipicker)
    implementation("androidx.media3:media3-exoplayer:1.6.0")
    implementation("androidx.media3:media3-ui:1.6.0")
    // CameraX dependencies
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.video)
    // Google Play In-App Updates
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)
    // ML Kit barcode scanning — powers the Front Desk QR scanner.
    implementation(libs.mlkit.barcode.scanning)

    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
