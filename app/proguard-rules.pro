# Retrofit — official keep rules (Retrofit 2.x with R8 full mode)
# https://github.com/square/retrofit/blob/master/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes Exceptions

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# R8 full mode replaces unreferenced Retrofit interfaces with null (because
# implementations are created via Proxy). Keep the interfaces.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# R8 full mode strips generic signatures for non-kept classes. Retrofit uses
# reflection on generics to resolve Call<T> / Response<T> / Continuation<T>.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-dontwarn retrofit2.**
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# Our API interfaces (used by Retrofit.create())
-keep interface me.proton.sync.api.** { *; }

# Gson
-keepattributes *Annotation*
-keep class me.proton.sync.api.models.** { *; }
# Contact filter rules are persisted as gson JSON in SharedPreferences, so their field AND enum
# constant names are part of a stored format that has to survive an app upgrade. The generic
# @SerializedName rule below covers fields but not enum constants; renaming those would make every
# saved rule unreadable, and a rule set that fails to parse means "sync everything", so users would
# silently lose their filters on the first minified build.
-keep class me.proton.sync.contacts.filter.** { *; }
# ContactNameMode is stored by constant name too, and it lives outside the package above. R8's
# built-in enum handling happens to preserve these today, which was verified by searching the
# release dex, but that is luck rather than intent: the setting is read back with a name match and
# a miss falls silently through to ANDROID, quietly restyling the contacts of anyone who had chosen
# otherwise. Named explicitly so the behaviour does not depend on a default staying put.
-keep class me.proton.sync.contacts.ContactNameMode { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn sun.misc.Unsafe

# Kotlin data classes deserialized by Gson (models already covered above, but
# keep no-arg constructors for any @Entity / data class touched via reflection).
-keepclassmembers class me.proton.sync.** {
    <init>(...);
}

# WorkManager Workers are instantiated reflectively by their runtime — R8 will
# otherwise strip their (Context, WorkerParameters) constructor.
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class me.proton.sync.EventLoopWorker { *; }
-keep class me.proton.sync.SyncWorker { *; }

# BouncyCastle, kept for the JCA provider ONLY. GopenPGP does the OpenPGP work since v1.9.1, and
# bcpg/bcutil went with it: `-keep class org.bouncycastle.openpgp.**` was still here afterwards and
# matched ZERO classes in the 1.11.0 mapping, so it has gone along with the comment that described
# a dependency this app no longer has.
#
# What remains is deliberately blunt. The provider registers its algorithms by reflection, and the
# BKS KeyStore it supplies is what OkHttp's TLS needs on Android 16, so a narrower rule risks losing
# networking and sign-in with no compile error to warn you. It is also the reason Play reports DEX
# optimisation as "Low": these 4289 classes are 77% of everything this build keeps, against 139 of
# our own. That is a property of the dependency, not of our rules, and it is not worth the blast
# radius to chase. Measured 2026-09-23.
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }

# ez-vcard
-keep class ezvcard.** { *; }
-dontwarn ezvcard.**

# ical4j
-keep class net.fortuna.ical4j.** { *; }
-dontwarn net.fortuna.ical4j.**

# ical4j + groovy + freemarker pull in a large set of server-side / desktop-only
# dependencies (FreeMarker, Groovy Grape, Apache Ivy, Jython, Xalan, Jaxen,
# java.beans, java.rmi, javax.swing, JRebel, XStream) that are not present on
# Android but are also never actually reached at runtime for our use case.
# Silence R8's missing-class errors for all of them.
-dontwarn freemarker.**
-dontwarn org.codehaus.groovy.**
-dontwarn groovy.**
-dontwarn org.apache.ivy.**
-dontwarn org.python.**
-dontwarn org.jaxen.**
-dontwarn org.apache.xml.utils.**
-dontwarn com.sun.org.apache.xml.internal.utils.**
-dontwarn com.thoughtworks.xstream.**
-dontwarn org.zeroturnaround.javarebel.**
-dontwarn java.beans.**
-dontwarn java.rmi.**
-dontwarn javax.swing.**

# OkHttp / BouncyCastle — both are commonly seen with -dontwarn on Android
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# Google Play Billing
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# GopenPGP gomobile bridge (native/gopenpgp-bridge). gomobile generates JNI stubs in the `bridge`
# and `go` packages whose names must survive minification: the native side resolves them by exact
# name, so obfuscation turns into UnsatisfiedLinkError at runtime rather than a build failure.
-keep class bridge.** { *; }
-keep class go.** { *; }
