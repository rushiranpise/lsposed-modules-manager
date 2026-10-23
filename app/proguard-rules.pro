# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep the JSON model names used for reflective debugging
-keepattributes SourceFile,LineNumberTable

# ML Kit loads its components through reflection. R8 full mode, the default in AGP 9, removes
# the constructors that lookup depends on, which makes LanguageIdentification.getClient() crash
# in release builds. These are the rules firebase-components 19 ships for this.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    void <init>();
}
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
