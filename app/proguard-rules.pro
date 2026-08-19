# kotlinx.serialization keeps generated serializers reachable via companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.dshmobile.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.dshmobile.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dshmobile.app.**$$serializer { *; }

# OkHttp / Okio ship optional platform hooks that R8 reports as missing.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**
