# ---- gomobile bind (mobile.aar): JNI + reflectively-invoked bind classes ----
# Mobile is the Go bind entry (Seq.setContext resolves it by name); HermesSession is
# used directly. Both back native JNI in libgojni.so and must keep exact member names.
-keep class com.hermesvox.mobile.Mobile { *; }
-keep class com.hermesvox.mobile.HermesSession { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.hermesvox.mobile.** { *; }
-keepclassmembers class com.hermesvox.mobile.** { native <methods>; }

# gomobile/Seq runtime (go.Seq / go.Universe are reflectively wired into the bind)
-keep class go.** { *; }

# ---- sherpa-onnx JNI (com.k2fsa.sherpa.onnx.*) ----
# Native libsherpa-onnx-jni.so registers methods by name; kill off any unused-
# class stripping so STT (Vad/OfflineRecognizer) + TTS (OfflineTts) survive shrink.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** { native <methods>; }

# ---- org.apache.commons.compress: service-loaders + reflection (tar/bzip2) ----
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ---- AI Edge litertlm native bridge (JNI + reflective tool dispatch) ----
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.litertlm.** { native <methods>; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniInferenceCallback { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniMessageCallback { *; }

# litertlm's reflective tool helper references kotlin-reflect, which is excluded
# from the runtime deps; suppress the R8 missing-class warning (generated rule).
-dontwarn kotlin.reflect.full.KClasses
