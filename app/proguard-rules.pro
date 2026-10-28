# WireGuard Native & JNI
-keep class com.wireguard.** { *; }
-dontwarn com.wireguard.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Strip verbose/debug logs in release builds. Keep i/w/e for diagnostics.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
