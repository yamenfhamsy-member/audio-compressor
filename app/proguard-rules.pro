# Add project specific ProGuard rules here.
-dontwarn io.github.jaredmdobson.concentus.**
# Native-backed libs must not be shrunk/obfuscated (JNI entry points).
-keep class ai.onnxruntime.** { *; }
-keep class org.vosk.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.vosk.**
-dontwarn org.jtransforms.**
