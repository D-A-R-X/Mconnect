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

// Firebase client config resolution.
//
// The app initialises Firebase programmatically from BuildConfig (see
// PushTokenManager.ensureFirebaseInitialized). Release CI supplies these via
// env vars, but a plain `./gradlew assembleDebug` on a dev machine leaves them
// blank — which silently disables push entirely (no token is ever registered,
// so NO notification of any kind can arrive). To make debug builds "just work",
// fall back to the standard google-services.json (drop your Firebase Android
// config into app/google-services.json — it holds only non-secret client keys
// that already ship inside every APK). Env vars still win when present.
val googleServicesJson: Map<String, String> = run {
    val f = project.file("google-services.json")
    if (!f.exists()) return@run emptyMap()
    runCatching {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
        val projectInfo = root["project_info"] as? Map<String, Any?> ?: emptyMap()
        val client = (root["client"] as? List<Map<String, Any?>>)?.firstOrNull() ?: emptyMap()
        val clientInfo = client["client_info"] as? Map<String, Any?> ?: emptyMap()
        val apiKey = (client["api_key"] as? List<Map<String, Any?>>)
            ?.firstOrNull()?.get("current_key") as? String
        mapOf(
            "FIREBASE_APPLICATION_ID" to (clientInfo["mobilesdk_app_id"] as? String ?: ""),
            "FIREBASE_PROJECT_ID" to (projectInfo["project_id"] as? String ?: ""),
            "FIREBASE_API_KEY" to (apiKey ?: ""),
            "FIREBASE_GCM_SENDER_ID" to (projectInfo["project_number"] as? String ?: ""),
            "FIREBASE_STORAGE_BUCKET" to (projectInfo["storage_bucket"] as? String ?: ""),
        )
    }.getOrDefault(emptyMap())
}
fun firebaseConfig(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: googleServicesJson[name]
        ?: ""

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
    envOrDefault("NEXT_PUBLIC_CONVEX_SITE_URL", "https://api-mfpl.theairix.com/")
)
val baseUrl = ensureTrailingSlash(
    envOrDefault("MCONNECT_BASE_URL", defaultBaseUrl)
)
val defaultAppUrl = ensureTrailingSlash(
    envOrDefault("NEXT_PUBLIC_APP_URL", "https://mms.aivida.in/")
)
val appUrl = ensureTrailingSlash(
    envOrDefault("MCONNECT_APP_URL", defaultAppUrl)
)

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
        versionCode = 11
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"${baseUrl}\"")
        buildConfigField("String", "APP_URL", "\"${appUrl}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${firebaseConfig("FIREBASE_APPLICATION_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseConfig("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseConfig("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_GCM_SENDER_ID", "\"${firebaseConfig("FIREBASE_GCM_SENDER_ID")}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${firebaseConfig("FIREBASE_STORAGE_BUCKET")}\"")
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
