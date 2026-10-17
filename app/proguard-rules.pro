-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# jaudiotagger instantiates frame bodies and field keys by class name.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**

# Moshi turns enums into JSON by looking up each constant's field by name (smart playlist rules,
# backups). Keep those names, or R8 renames them and creating the adapter crashes.
-keepclassmembers enum dev.yuwixx.resonance.** {
    <fields>;
}
