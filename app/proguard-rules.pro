# Conscrypt TLS
-keep class org.conscrypt.** { *; }

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Gson / Retrofit
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
