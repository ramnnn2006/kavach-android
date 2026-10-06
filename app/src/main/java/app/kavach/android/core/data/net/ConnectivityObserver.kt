package app.kavach.android.core.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** navigator.onLine + the 'online' event, for Android. */
@Singleton
class ConnectivityObserver @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /**
     * Every network with internet we were told about. Asking for the active network inside
     * onLost() is unreliable during a Wi-Fi → mobile handover (it can still be null), and the
     * mobile network that stays up gets no second onAvailable(), so the app would think it is
     * offline until the next change. Tracking the set avoids that.
     */
    private val networks = HashSet<Network>()

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(networks) { networks.add(network) }
                _isOnline.value = true
            }
            override fun onLost(network: Network) {
                val any = synchronized(networks) {
                    networks.remove(network)
                    networks.isNotEmpty()
                }
                _isOnline.value = any
            }
            override fun onUnavailable() { _isOnline.value = false }
        })
    }

    private fun currentlyOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
