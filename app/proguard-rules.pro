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
