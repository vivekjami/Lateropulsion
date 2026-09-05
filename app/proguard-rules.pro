# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lateropulsion.**$$serializer { *; }
-keepclassmembers class com.lateropulsion.** { *** Companion; }
-keepclasseswithmembers class com.lateropulsion.** { kotlinx.serialization.KSerializer serializer(...); }
# SQLCipher
-keep class net.zetetic.database.** { *; }
# No crash reporters or analytics are permitted in this app (REQ-SEC-004).
