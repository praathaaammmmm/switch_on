# kotlinx.serialization: keep generated serializers for the API DTOs.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep,includedescriptorclasses class com.switchon.customer.data.remote.**$$serializer { *; }
-keepclassmembers class com.switchon.customer.data.remote.** { *** Companion; }
-keepclasseswithmembers class com.switchon.customer.data.remote.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit needs the generic signatures of suspend API methods.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (used by EncryptedSharedPreferences) references compile-time-only annotations.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
