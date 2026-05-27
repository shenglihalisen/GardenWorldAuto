# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Timber
-dontwarn timber.log.Timber

# Keep Compose
-keep class androidx.compose.** { *; }
-keep class androidx.compose.material3.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep AI Edge SDK
-keep class com.google.ai.edge.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# Keep MediaPipe
-keep class com.google.mediapipe.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keep class com.gardenworld.auto.** { *; }

# General
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
