-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# jaudiotagger instantiates frame bodies and field keys by class name.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
