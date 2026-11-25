-dontobfuscate
-dontoptimize
-keepclasseswithmembers class * {
    native <methods>;
}
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**
