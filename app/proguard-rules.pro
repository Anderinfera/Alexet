-dontwarn coil3.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn androidx.media3.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-keep class dev.ander.alexet.integration.NextPlayerClient { *; }
# Debug log (AppLog.describeChanges) prints changed fields by name.
-keepclassmembernames class dev.ander.alexet.data.AppSettings { <fields>; }
-keepclassmembernames class dev.ander.alexet.ui.gallery.Interaction { <fields>; }
# Commons Compress: optional Zstandard support (not used; .zst archives are not opened).
-dontwarn com.github.luben.zstd.**
# Room builds its databases by reflection (WorkManager's comes with LiteRT): keep their constructors.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
