# Keep Filament/SceneView native bindings
-keep class com.google.android.filament.** { *; }
-keep class io.github.sceneview.** { *; }

# Retrofit (official rules — not shipped as consumer rules by the library)
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# Protobuf + gRPC (NVIDIA Riva TTS / voice cloning)
#
# protobuf-javalite finds fields by their generated names via reflection, so
# R8 renaming them breaks it at runtime with:
#   "Field audioPrompt_ for X3.i not found"
# which is exactly what voice cloning hit on device. Keep the generated
# message classes, their fields, and the static accessors protobuf looks up.
# ---------------------------------------------------------------------------
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-keep class nvidia.riva.** { *; }
-keepclassmembers class nvidia.riva.** { *; }

# gRPC picks its transport and name resolver through the ServiceLoader.
-keep class io.grpc.** { *; }
-keep class io.grpc.okhttp.** { *; }
-keepclassmembers class * implements io.grpc.ManagedChannelProvider { *; }
-keepclassmembers class * implements io.grpc.NameResolverProvider { *; }
-keepnames class io.grpc.internal.** { *; }
-dontwarn io.grpc.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.**

# Room entities are read reflectively by the generated DAOs.
-keep class com.trellis.studio.data.entity.** { *; }

# ---------------------------------------------------------------------------
# Agent services
#
# The framework instantiates these by their manifest class name and calls into
# their lifecycle methods, so the class names and members must survive R8. An
# accessibility service that gets renamed or has its overrides stripped binds
# but does nothing — which the system then reports as "not working". Keeping
# them outright removes that whole failure mode from release builds.
# ---------------------------------------------------------------------------
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class com.trellis.studio.service.AutomationService { *; }
-keep class com.trellis.studio.service.FloatingOverlayService { *; }
-keep class com.trellis.studio.service.GenerationService { *; }
