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

# ---- org.apache.commons.compress: runtime bzip2/tar path (ModelDownloader.unpkg) ----
# ModelDownloader.kt:122-123 constructs BZip2CompressorInputStream + TarArchiveInputStream
# directly. Keep the stream-graph attributes (InnerClasses/Signature) + the class-names
# + the ServiceLoader/providers used by the CompressorStreamFactory/TarArchiveInputStream
# reflection path, or R8 strips the factory wiring and the model unpack throws at runtime.
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,Annotation,EnclosingMethod
-keep,includedescriptorclasses class org.apache.commons.compress.** { *; }
-keep,includedescriptorclasses class org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream { <init>(...); *; }
-keep,includedescriptorclasses class org.apache.commons.compress.archivers.tar.TarArchiveInputStream { <init>(...); *; }
-keepclassmembers,includedescriptorclasses class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# ---- AI Edge litertlm native bridge (JNI + reflective tool dispatch) ----
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.litertlm.** { native <methods>; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniInferenceCallback { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$JniMessageCallback { *; }

# 0.6.6 (field crash, Enhanced Realtime → tool call → Gemma flow close):
# NoSuchMethodError SendChannel.close$default INSIDE litertlm's own
# sendMessageAsync callback (Conversation$sendMessageAsync$1$1.onDone →
# SendChannel.close$default). The AAR's precompiled bytecode calls the
# DefaultImpls-style static; R8's output kept the litertlm classes whole (the
# keep rule above) but the kotlinx.coroutines SendChannel machinery was
# rewritten/optimized such that the exact method reference no longer
# resolved at runtime. Keep the channels API surface litertlm's callback
# path links against, whole.
-keep class kotlinx.coroutines.channels.SendChannel { *; }
-keep class kotlinx.coroutines.channels.SendChannel$DefaultImpls { *; }
-keep class kotlinx.coroutines.channels.ProducerScope { *; }

# litertlm's reflective tool helper references kotlin-reflect, which is excluded
# from the runtime deps; suppress the R8 missing-class warning (generated rule).
-dontwarn kotlin.reflect.full.KClasses
