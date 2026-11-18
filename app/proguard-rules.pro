# ##############################################################################
# ## 1. REGOLE DI BASE ANDROID
# ##############################################################################
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions

# Eccezioni e Viste Custom
-keep public class * extends java.lang.Exception
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Parcelable e Enum
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# SearchView e componenti UI
-keep class androidx.appcompat.widget.SearchView { *; }

# ##############################################################################
# ## 2. WEBRTC & CHIAMATE (CRITICO)
# ##############################################################################
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep class com.lollo1344.cryptionchat.WebRtcClient { *; }
-keep class com.lollo1344.cryptionchat.WebRtcSignal { *; }
-keep class com.lollo1344.cryptionchat.SignalData { *; }

# ##############################################################################
# ## 3. RETROFIT, GSON & NETWORKING
# ##############################################################################
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }

# ##############################################################################
# ## 4. LIBRERIE DI TERZE PARTI
# ##############################################################################
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$ImageType { *; }

-keep class com.yalantis.ucrop.** { *; }
-keep interface com.yalantis.ucrop.** { *; }

-keep class ja.burhanrashid52.photoeditor.** { *; }
-keep interface ja.burhanrashid52.photoeditor.** { *; }

-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

-keep class androidx.security.crypto.** { *; }

# ##############################################################################
# ## 5. MODELLI DATI DELL'APP
# ##############################################################################
-keep class com.lollo1344.cryptionchat.** { *; }
-keep public interface com.lollo1344.cryptionchat.ApiService { *; }

# --- VOSK & JNA RULES ---
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.*
-dontwarn com.sun.jna.**

# --- SHIZUKU ---
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# --- SQLCIPHER RULES ---
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**