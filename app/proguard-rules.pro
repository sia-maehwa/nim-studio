# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.siamaehwa.nimstudio.** {
    *** Companion;
}
-keepclasseswithmembers class com.siamaehwa.nimstudio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Compose (consumer rules shipped by androidx are sufficient; nothing extra needed)
