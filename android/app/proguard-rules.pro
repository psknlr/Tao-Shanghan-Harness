# kotlinx.serialization：保留 @Serializable 類的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.impfai.hermes.** {
    *** Companion;
}
-keepclasseswithmembers class org.impfai.hermes.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit
-keepattributes Signature, Exceptions
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
