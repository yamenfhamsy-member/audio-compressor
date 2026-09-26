# Add project specific ProGuard rules here.
-dontwarn io.github.jaredmdobson.concentus.**
# Native-backed libs must not be shrunk/obfuscated (JNI entry points).
-keep class ai.onnxruntime.** { *; }
-keep class org.vosk.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.vosk.**
-dontwarn org.jtransforms.**
# JTransforms' large-array helper references desktop-only sun.misc.Cleaner;
# we never allocate >2^31 elements, so this path is dead on Android.
-dontwarn pl.edu.icm.**
-dontwarn sun.misc.**
