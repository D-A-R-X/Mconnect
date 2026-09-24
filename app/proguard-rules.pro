# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Retrofit and Gson use annotations/reflection for API interfaces and JSON
# payload fields. Keep the runtime metadata and model field names while allowing
# the rest of the app code to be shrunk and obfuscated by R8.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.manjugroups.m_connect.network.** {
    <fields>;
}
-keepclassmembers class com.manjugroups.m_connect.auth.** {
    <fields>;
}
-keepclassmembers class com.manjugroups.m_connect.geotrack.data.** {
    <fields>;
}
-keepclassmembers class com.manjugroups.m_connect.ui.** {
    <fields>;
}
-keepclassmembers class com.manjugroups.m_connect.notifications.** {
    <fields>;
}

-keep interface com.manjugroups.m_connect.network.** { *; }
-keep class com.manjugroups.m_connect.network.** { *; }

# Gson's TypeToken reads the generic type from the anonymous subclass Signature.
# R8 must keep both the metadata above and the TypeToken hierarchy itself, or
# minified release builds can crash while restoring cached generic JSON.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Component registrars: keep their no-arg constructors ─────────────────────
# ML Kit (and Firebase) instantiate these BY REFLECTION from the class names in
# <meta-data android:name="com.google.firebase.components:…"> in the manifest.
# R8 full mode — the default on AGP 9 — does not treat a manifest meta-data
# string as a use of the constructor and strips <init>(), so instantiation
# fails, the registrar is silently skipped, and its components are never
# provided. For ML Kit that left SharedPrefManager null: the Front Desk QR
# scanner crashed on its first camera frame (NPE in InputImage.fromMediaImage)
# on every Play build since R8 was enabled on 5 Sep, while debug builds, which
# are not shrunk, worked. Verified in the release dex on 2026-09-24:
# CommonComponentRegistrar, VisionCommonRegistrar and BarcodeRegistrar had 0
# constructors in release and 1 in debug.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
