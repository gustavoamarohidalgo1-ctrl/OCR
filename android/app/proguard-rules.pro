# R8 rules for the release build.
#
# ML Kit and WorkManager ship their own consumer rules. The three libraries below
# ship none, and their native code looks up Java classes, fields and methods by
# name through JNI (exceptions, tensors, Mat/Pix handles, progress callbacks).
# Keep them whole: no removal, no renaming, no member optimization.

# ONNX Runtime (PaddleOCR models).
-keep class ai.onnxruntime.** { *; }

# OpenCV (illumination, paper boundary, text rectification).
-keep class org.opencv.** { *; }

# tesseract4android (Tesseract and Leptonica wrappers).
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }

# Readable stack traces in crash reports; mapping.txt maps the rest.
-keepattributes SourceFile,LineNumberTable
