# TDLib talks to native code through JNI; names must stay.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**
# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# VLC engine (native code calls into these classes)
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.**
# Harder to read / re-package
-repackageclasses 'a'
-allowaccessmodification

# Encrypted storage (Tink) and annotation-only classes that R8 reports as missing
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }

# Media3 session service and Google sign-in
-dontwarn androidx.media3.**
-keep class com.google.android.libraries.identity.googleid.** { *; }
