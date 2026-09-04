package io.github.mr1ve3r.combined.engine

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A VPN protocol implementation, as the host sees it.
 *
 * Both `L2tpEngine` and `SstpEngine` implement this, and the host dispatches on
 * the profile type alone. Nothing below this interface may touch `VpnService`
 * or `VpnService.Builder`: an engine that can configure the interface itself
 * ends up competing with the host for one system resource, and only one
 * `VpnService` may be active on Android.
 *
 * The lifecycle is deliberately split in two, because the TUN device is built
 * between the halves:
 *
 * ```
 * val params = engine.connect(profile, protector)   // transport up, PPP negotiated
 * val fd = tunnelBuilder.build(params, perAppConfig) // host builds the interface
 * engine.attachTun(fd)                               // packets start flowing
 * ...
 * engine.disconnect()
 * ```
 *
 * Implementations are single-use per connection unless they document otherwise,
 * and must be safe to [disconnect] from a different thread than the one that
 * called [connect].
 */
interface VpnEngine {
    /** Current lifecycle state. Starts at [EngineState.Idle]. */
    val state: StateFlow<EngineState>

    /**
     * Log events from this engine, already stripped of secrets.
     *
     * A hot stream: events emitted before a subscriber arrives may be dropped,
     * subject to the replay the implementation chooses.
     */
    val events: SharedFlow<EngineLogEvent>

    /**
     * Establishes the transport and negotiates the protocol, returning the
     * parameters the server agreed to.
     *
     * The TUN device does **not** exist yet when this returns — the host builds
     * it from the returned [TunnelParams] and hands it back via [attachTun].
     *
     * Every socket opened here must be passed to [protector] before `connect()`
     * is called on it. That applies to the socket to an HTTP proxy as much as to
     * the socket to the server.
     *
     * @param profile the connection to establish. An implementation may reject a
     *   profile of the wrong protocol with [IllegalArgumentException].
     * @param protector used to keep the engine's own sockets outside the tunnel.
     * @return parameters agreed with the server.
     * @throws EngineException if the connection could not be established.
     */
    suspend fun connect(profile: EngineProfile, protector: SocketProtector): TunnelParams

    /**
     * Hands the engine the TUN device built from the parameters [connect]
     * returned. The engine starts moving packets and takes ownership of [fd],
     * closing it during [disconnect].
     */
    fun attachTun(fd: ParcelFileDescriptor)

    /**
     * Shuts the tunnel down: protocol-level disconnect where the protocol has
     * one, then sockets closed, coroutines cancelled, and the TUN descriptor
     * released.
     *
     * Safe to call in any state, including before [connect] and more than once.
     * Must not throw, and must complete within a bounded time even when the
     * server has stopped responding.
     */
    suspend fun disconnect()
}

/**
 * Thrown by [VpnEngine.connect] when a connection cannot be established.
 *
 * @property error the failure, in the shared vocabulary the UI understands.
 */
class EngineException(
    val error: EngineError,
    cause: Throwable? = null,
) : Exception(error.detail ?: error.messageKey, cause)
