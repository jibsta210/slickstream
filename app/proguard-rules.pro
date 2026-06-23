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
