# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\asus\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt

# MapLibre Native rules
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
