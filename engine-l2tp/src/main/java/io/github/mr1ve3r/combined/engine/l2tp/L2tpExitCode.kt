package io.github.mr1ve3r.combined.engine.l2tp

import io.github.mr1ve3r.combined.engine.EngineError

/**
 * Exit codes of the native L2TP engine, and their translation into the shared
 * [EngineError] vocabulary (SPEC phase 4.1.4).
 *
 * The values mirror `TUNNEL_EXIT_*` in `android/app/src/main/cpp/engine.h` and
 * must be kept in step with it. The narrative version of this table, including
 * why some codes land where they do, is in `docs/ARCHITECTURE.md`.
 *
 * The [EngineError.detail] strings are the ones the user interface has always
 * shown for these codes. Phase 4 changes the shape of the L2TP path and not its
 * behaviour, so the text stays byte-identical; [EngineError.messageKey] is what
 * the localised UI will move to later.
 */
object L2tpExitCode {
    /** The tunnel ran and shut down cleanly. */
    const val OK: Int = 0

    /** IKE phase 1 or phase 2 failed. Usually a wrong PSK or an unsupported proposal. */
    const val IKE_FAILED: Int = 1

    /** The L2TP control channel failed to come up over a working IPsec SA. */
    const val L2TP_FAILED: Int = 2

    /** PPP negotiation failed: LCP, authentication or IPCP. */
    const val PPP_FAILED: Int = 3

    /** The poll loop hit an I/O error after the tunnel was already up. */
    const val POLL_ERROR: Int = 4

    /** The engine was called with arguments it could not use. A bug in the host. */
    const val BAD_ARGS: Int = 10

    /** A proxy-mode transport was requested that the native layer does not implement. */
    const val PROXY_NOT_IMPLEMENTED: Int = 11

    /** The tunnel stopped because it was asked to. Not a failure. */
    const val STOPPED: Int = 12

    /**
     * Maps a native exit code onto an [EngineError].
     *
     * @return `null` for [OK] and [STOPPED], the two codes that do not describe
     *   a failure. Callers must handle that case rather than inventing an error
     *   for a deliberate shutdown.
     */
    fun toEngineError(code: Int): EngineError? = when (code) {
        OK, STOPPED -> null
        IKE_FAILED -> EngineError.IpsecFailed(IKE_DETAIL)
        // There is no L2TP-specific variant in EngineError, and inventing one
        // for a single protocol would push protocol detail into the shared
        // vocabulary. The control channel is what carries PPP, so the failure
        // is reported as the negotiation it belongs to, named by its phase.
        L2TP_FAILED -> EngineError.PppNegotiationFailed(phase = "L2TP", detail = L2TP_DETAIL)
        // The native layer does not yet distinguish an authentication
        // rejection from any other PPP failure, so this cannot map to
        // AuthenticationFailed. See docs/ARCHITECTURE.md.
        PPP_FAILED -> EngineError.PppNegotiationFailed(phase = "PPP", detail = PPP_DETAIL)
        // Reached only after the tunnel was up, so the transport was working
        // and has stopped working: a lost network, not a misconfiguration.
        POLL_ERROR -> EngineError.NetworkUnreachable(POLL_DETAIL)
        BAD_ARGS -> EngineError.Internal(BAD_ARGS_DETAIL)
        PROXY_NOT_IMPLEMENTED -> EngineError.Internal(PROXY_DETAIL)
        else -> EngineError.Internal(unknownDetail(code))
    }

    /** Whether [code] means the tunnel ended without a failure to report. */
    fun isCleanExit(code: Int): Boolean = code == OK || code == STOPPED

    private const val IKE_DETAIL = "IPsec negotiation failed. Check the PSK and server settings."
    private const val L2TP_DETAIL = "L2TP handshake failed."
    private const val PPP_DETAIL = "PPP negotiation failed."
    private const val POLL_DETAIL = "Tunnel poll I/O error."
    private const val BAD_ARGS_DETAIL = "Invalid tunnel arguments from the app."
    private const val PROXY_DETAIL = "Proxy transport is not implemented yet."

    private fun unknownDetail(code: Int): String = "Tunnel engine exited with code $code"
}
