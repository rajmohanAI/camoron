# Navigation routes are @Serializable objects; keep their generated serializers.
-keepclassmembers class com.camoron.app.navigation.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
