# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/eehouse/android/android-sdk-linux/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep everything. Useful maybe for confirming that proguard's causing
#  a problem?
# -keep class ** { *; }

# Don't turn this on until it's clear that proguard isn't messing
# anything else up. It'll make crash reports useless.

-dontobfuscate

# Uncomment this if obfuscation is enabled (by removing the line
# above), and save the mapping.txt file or confirm it can be rebuilt
# from a tag.
# -keepattributes SourceFile,LineNumberTable

# Prevents crash when jni code calls setInt on various jin.* classes
-keep public class org.eehouse.android.xw4.jni.** { public *; }
