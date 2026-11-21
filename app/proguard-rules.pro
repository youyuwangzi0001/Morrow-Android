-keep class studio.morrow.android.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature

# kotlinx.serialization：保留序列化器
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class studio.morrow.android.**$$serializer { *; }
-keepclassmembers class studio.morrow.android.** { *** Companion; }
-keepclasseswithmembers class studio.morrow.android.** { kotlinx.serialization.KSerializer serializer(...); }