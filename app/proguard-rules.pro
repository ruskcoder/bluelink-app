# Release shrinking rules for everything R8 cannot see being used.

# --- iMessage core (Rust, through UniFFI and JNA) ---------------------------------------------------
# The generated bindings call into the native library through JNA, which finds structures, callbacks
# and their fields by reflection. Renaming or removing any of it breaks the bridge at runtime, not at
# build time, so all of it is kept.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
# Kotlin classes implementing UniFFI callback interfaces are invoked from Rust.
-keep class * implements uniffi.imessage_core.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# --- whisper.cpp ----------------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Games extension protocols (AIDL, bound across processes) -------------------------------------
-keep class com.bluebubbles.** { *; }
-keep class com.ruskcoder.messages.games.** { *; }

# --- ML Kit (QR scanning) -------------------------------------------------------------------------
# ML Kit wires its scanners together with Firebase's component runtime, which discovers registrars
# through manifest meta-data and looks components up by class. R8 full mode optimises that apart and
# BarcodeScanning.getClient() then finds no component and throws an NPE, so the whole chain is kept.
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }

# --- kotlinx.serialization ------------------------------------------------------------------------
# Serializers for @Serializable classes are looked up through their companions.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Libraries with optional dependencies they probe for --------------------------------------------
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
