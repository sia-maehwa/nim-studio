# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.siamaehwa.nimstudio.** {
    *** Companion;
}
-keepclasseswithmembers class com.siamaehwa.nimstudio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 모델/DTO 필드명 유지 (직렬화 및 디버깅 안전성)
-keepnames class com.siamaehwa.nimstudio.core.** { *; }
-keepnames class com.siamaehwa.nimstudio.core.db.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.siamaehwa.nimstudio.core.db.**_Impl { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Compose (consumer rules shipped by androidx are sufficient; nothing extra needed)
