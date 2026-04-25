plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

fun envOrEmpty(name: String): String = System.getenv(name) ?: ""
fun envOrDefault(name: String, defaultValue: String): String = System.getenv(name) ?: defaultValue
fun ensureTrailingSlash(url: String): String = if (url.endsWith("/")) url else "$url/"
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
val defaultBaseUrl = ensureTrailingSlash(
    envOrDefault("NEXT_PUBLIC_CONVEX_SITE_URL", "https://opulent-cricket-895.convex.site/")
)
val baseUrl = ensureTrailingSlash(
    envOrDefault("MCONNECT_BASE_URL", defaultBaseUrl)
)

android {
    namespace = "com.manjugroups.m_connect"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.manjugroups.m_connect"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"${baseUrl}\"")
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
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
