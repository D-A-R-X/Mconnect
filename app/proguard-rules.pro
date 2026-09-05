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
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
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
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
