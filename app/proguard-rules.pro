# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep the JSON model names used for reflective debugging
-keepattributes SourceFile,LineNumberTable

# The Xposed entry class is referenced only by META-INF/xposed/java_init.list, which R8
# cannot see, so keep it and its no-arg constructor by name.
-keep class com.rushi.modulesmanager.ModulesManagerXposedModule {
    <init>();
}
