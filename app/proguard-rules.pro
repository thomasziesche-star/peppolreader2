# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep line numbers for readable release crash reports; hide the original .kt file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# PDFBox-Android (ZUGFeRD/Factur-X embedded XML extraction)
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Fragments are instantiated by name from res/navigation/nav_graph.xml via reflection,
# so their classes and no-arg constructors must survive shrinking/obfuscation.
-keep public class * extends androidx.fragment.app.Fragment

# WorkManager instantiates the due-date worker by class name (default WorkerFactory).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room entities are populated reflectively by the generated DAO code; keep their members.
-keep class com.ziesche.peppolreader.data.model.** { *; }
-keep class com.ziesche.peppolreader.creator.model.** { *; }

# org.json ships with the Android framework — never bundled, don't warn about it.
-dontwarn org.json.**