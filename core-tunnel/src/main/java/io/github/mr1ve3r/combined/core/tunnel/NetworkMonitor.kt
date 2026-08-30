package io.github.mr1ve3r.combined.core.tunnel

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Publishes changes of the active network as a [Flow].
 *
 * Used for reconnect decisions now, and for protocol auto-selection later
 * (SPEC phase 10.2). It replaces upstream Open SSTP Client's `NetworkObserver`,
 * which served only SSTP; one monitor serves both engines.
 *
 * The flow is cold: the `NetworkCallback` is registered when collection starts
 * and unregistered when it stops, so nothing leaks if the collector is
 * cancelled.
 *
 * @property connectivityManager the system connectivity service.
 */
class NetworkMonitor(
    private val connectivityManager: ConnectivityManager,
) {
    /**
     * Emits whenever the default network appears, disappears, or changes
     * transport.
     *
     * Consecutive identical states are suppressed: the platform is chatty about
     * capability changes that do not affect routing, and a reconnect triggered
     * by each one would be worse than no reconnect at all.
     */
    fun events(): Flow<NetworkEvent> = callbackFlow {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(NetworkEvent.Available(network, transportOf(network)))
                }

                override fun onLost(network: Network) {
                    trySend(NetworkEvent.Lost(network))
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(NetworkEvent.Available(network, transportOf(capabilities)))
                }
            }

        // The tunnel's own interface is a network with INTERNET too, and it
        // appears and disappears with every connect. Both consumers of this flow
        // — the reconnect in the host and the protocol auto-selection of phase
        // 10.2 — are asking about the network *underneath* the tunnel, so the
        // tunnel is filtered out here rather than in each of them.
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun transportOf(network: Network): NetworkTransport = transportOf(connectivityManager.getNetworkCapabilities(network))

    private fun transportOf(capabilities: NetworkCapabilities?): NetworkTransport = when {
        capabilities == null -> NetworkTransport.OTHER
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        else -> NetworkTransport.OTHER
    }
}

/** A change in network availability. */
sealed interface NetworkEvent {
    /**
     * A network is usable.
     *
     * @property network the platform handle.
     * @property transport how it connects, for rules that care (SPEC phase 10.2).
     */
    data class Available(val network: Network, val transport: NetworkTransport) : NetworkEvent

    /**
     * A network went away.
     *
     * @property network the platform handle.
     */
    data class Lost(val network: Network) : NetworkEvent
}

/** Coarse transport type of a network. */
enum class NetworkTransport {
    /** Wi-Fi. */
    WIFI,

    /** Mobile data. */
    CELLULAR,

    /** Wired. */
    ETHERNET,

    /** Anything else, including an unknown or already-torn-down network. */
    OTHER,
}
