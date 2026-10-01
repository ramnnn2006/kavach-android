# kotlinx.serialization: keep generated serializers for @Serializable models and routes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.kavach.android.** {
    *** Companion;
}
-keepclasseswithmembers class app.kavach.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.kavach.android.**$$serializer { *; }

# Type-safe navigation looks route serializers up by class at runtime (hasRoute(KClass),
# getBackStackEntry / popBackStack with a route object): KClass.serializer() reads the
# INSTANCE field of a data object route and its serializer() by name.
-keepclassmembers @kotlinx.serialization.Serializable class app.kavach.android.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / supabase-kt
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
# supabase-kt builds its HttpClient without an explicit engine: Ktor finds the OkHttp engine
# through ServiceLoader, so its container must survive shrinking.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
# Ktor's debug detector references JVM-only classes that don't exist on Android
-dontwarn java.lang.management.**
