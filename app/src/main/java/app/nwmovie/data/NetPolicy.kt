package app.nwmovie.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

/** Tracks the current connection and enforces the Wi-Fi-only / mobile-data switches. */
object NetPolicy {
    val online = MutableStateFlow(true)
    val metered = MutableStateFlow(false)
    val cellular = MutableStateFlow(false)

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    online.value = true
                    metered.value = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    cellular.value = c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                }
                override fun onLost(n: Network) { online.value = false }
            })
        } catch (_: Exception) { }
    }

    /** Returns a human readable reason if streaming must be blocked, else null. */
    fun streamBlock(p: Prefs): String? = when {
        !online.value -> "You're offline. Check your connection."
        cellular.value && !p.mobileData -> "Mobile data is turned off for NWFlix (Settings → Network & Download)."
        metered.value && p.wifiOnlyStream -> "Wi-Fi only streaming is on. Connect to Wi-Fi or turn it off in Settings → Network & Download."
        else -> null
    }
}
