# kotlinx.serialization keeps its generated serializers via @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.shivam.sketchseed.** {
    *** Companion;
}
-keepclasseswithmembers class com.shivam.sketchseed.** {
    kotlinx.serialization.KSerializer serializer(...);
}
