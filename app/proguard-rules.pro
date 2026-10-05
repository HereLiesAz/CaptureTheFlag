# secp256k1 (Nostr signatures): the JNI half is found and bound by name at runtime.
-keep class fr.acinq.secp256k1.** { *; }

# Wire types are (de)serialized by kotlinx.serialization through their generated serializers;
# the library ships its own rules, these keep the protocol's companions for reflection-free lookup.
-keepclassmembers @kotlinx.serialization.Serializable class com.hereliesaz.capturetheflag.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor and its engines reference optional classes that aren't on Android.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
