-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
-keep class net.zetetic.database.** { *; }

# Release: vyřadit ladicí/informační logy (nesmí unikat osobní údaje do logcatu)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
