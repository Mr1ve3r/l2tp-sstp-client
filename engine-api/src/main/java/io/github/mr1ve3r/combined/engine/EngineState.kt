package io.github.mr1ve3r.combined.engine

/**
 * Lifecycle state of an engine, published through [VpnEngine.state].
 *
 * The host drives the UI and its notification from this, so the states are the
 * ones a user can distinguish, not the ones the protocol happens to have.
 */
sealed interface EngineState {
    /** Nothing has been attempted yet. The initial state of every engine. */
    data object Idle : EngineState

    /**
     * A connection attempt is in progress.
     *
     * @property stage coarse progress description for the UI, such as
     *   `tls_handshake` or `ppp_negotiation`.
     */
    data class Connecting(val stage: String) : EngineState

    /**
     * The tunnel is up and carrying packets.
     *
     * @property params parameters agreed with the server.
     * @property since connection time in milliseconds since the epoch, used for
     *   the session timer.
     */
    data class Connected(
        val params: TunnelParams,
        val since: Long,
    ) : EngineState

    /**
     * The connection dropped and the engine is retrying.
     *
     * @property attempt 1-based attempt counter.
     * @property cause why the previous attempt ended.
     */
    data class Reconnecting(
        val attempt: Int,
        val cause: EngineError,
    ) : EngineState

    /**
     * The connection attempt failed and the engine has stopped trying.
     *
     * @property error why it failed.
     */
    data class Failed(val error: EngineError) : EngineState

    /** The tunnel was shut down deliberately. */
    data object Disconnected : EngineState
}
