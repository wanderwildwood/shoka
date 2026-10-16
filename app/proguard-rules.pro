# zxing-cpp builds its results from native code, which R8 cannot see.
-keep class zxingcpp.** { *; }
