-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
-keep class net.zetetic.database.** { *; }

# Release: vyřadit přímá volání Log.v/d/i/w (např. ze třetích stran), aby neunikaly PII do logcatu;
# PLog.w se v release směruje v Pulse4App přes Log.e("[WARN] ...") s redigovanou zprávou (NEW-10).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
