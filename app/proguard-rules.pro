# Shrink + optimize, but DON'T rename. Removing unused code/resources + ABI filtering is where the
# size win comes from; name-mangling saves little and is the usual source of reflection crashes
# (Retrofit interfaces, kotlinx.serialization, libtorrent JNI). Keeping names makes the production
# build safe to ship via self-update without an on-device test pass. Revisit once proven.
-dontobfuscate

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
-keep,includedescriptorclasses class com.slickstream.**$$serializer { *; }
-keepclassmembers class com.slickstream.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# libtorrent4j (JNI)
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# Media3
-keep class androidx.media3.** { *; }

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Google Sign-In via Credential Manager (defensive — keeps the request/response classes our code
# references so a future obfuscation pass can't break sign-in; the actual provider is in Play Services)
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**

# App @Serializable models / DTOs (defensive — keep names so generated serializers resolve)
-keep @kotlinx.serialization.Serializable class com.slickstream.** { *; }
-keepclassmembers enum com.slickstream.** { *; }

# Hilt / Dagger generated graph + injected ViewModels
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Optional / transitive deps that R8 may warn about (warnings fail the build otherwise)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.firebase.**
-dontwarn javax.annotation.**

# Keep enough JNI/native plumbing for libtorrent's swig layer (also covered above; explicit here)
-keepclasseswithmembernames class * {
    native <methods>;
}
