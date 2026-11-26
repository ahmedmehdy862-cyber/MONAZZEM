# ProGuard / R8 rules for the release build (م7).
#
# The app grew ~10 MB (PDFBox-Android + Compose + material-icons-extended) with
# minification off. R8 + resource shrinking reclaim the unused code/resources.
# Verified statically: the app has no reflection (no Class.forName / getIdentifier)
# and no Room; R.raw.* references are direct code references, so shrinkResources
# is safe. The GitHub release workflow build (assembleRelease) is the real gate:
# if it fails on a keep rule, fix the rule there, never silently disable R8 again.

# --- PDFBox-Android (org.apache.pdfbox / fontbox, repackaged as com.tom_roush) ---
# Loads classes and font resources reflectively; keep the whole library.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**
-dontwarn java.awt.**
-dontwarn javax.**

# --- ML Kit face detection (bundled model, avatar likeness) ---
# The library ships its own consumer rules; these are explicit insurance for
# the detector factory and the vision-face internals R8 might otherwise strip.
-keep class com.google.mlkit.vision.face.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
# Hotfix 0.33.1: the ML Kit init path discovers components by reflection
# (ComponentDiscovery loads the registrars by class name), so the common
# internals must keep their names too — not just vision.*.
-keep class com.google.mlkit.common.** { *; }
-dontwarn com.google.mlkit.**

# --- Adhan (prayer-time calculations) ---
-keep class com.batoulapps.adhan.** { *; }
-dontwarn com.batoulapps.adhan.**

# --- Android components instantiated by the framework by name ---
# (R8's default rules already keep manifest components; these are explicit
# insurance for the services the system starts on its own.)
-keep class com.focusstudy.poc.AppBlockAccessibilityService { *; }
-keep class com.focusstudy.poc.BlockVpnService { *; }
-keep class com.focusstudy.poc.AdhanPlaybackService { *; }
-keep class com.focusstudy.poc.FocusApp { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# --- Data models read/written through org.json (field names are code, not reflection) ---
# Kept as a safety net only; nothing here is reflectively instantiated.
-keep class com.focusstudy.poc.**$* { *; }

# --- Standard keeps ---
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
