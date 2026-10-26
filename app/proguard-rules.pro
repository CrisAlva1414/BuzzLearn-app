# Add project specific ProGuard rules here.

# Keep kotlinx.serialization generated serializers (validated in the release slice).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer {
    *** descriptor;
}
