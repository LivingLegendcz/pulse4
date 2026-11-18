-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
-keep class net.zetetic.database.** { *; }

# Release: vyřadit ladicí/informační/varovné logy (nesmí unikat osobní údaje do logcatu);
# Log.e ponechán pouze pro chyby bez PII (ošetřeno přes PLog.redact()).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
