# zxing-cpp builds its results from native code, which R8 cannot see.
-keep class zxingcpp.** { *; }

# Tesseract and Leptonica's native code calls back into these classes by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
