package android.net.wifi
class WifiManager {
    class MulticastLock { fun setReferenceCounted(b: Boolean) {}; fun acquire() {}; val isHeld: Boolean get() = false }
    fun createMulticastLock(tag: String): MulticastLock = MulticastLock()
}
