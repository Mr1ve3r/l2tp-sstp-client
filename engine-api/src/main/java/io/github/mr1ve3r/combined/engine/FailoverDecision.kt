package io.github.mr1ve3r.combined.engine

/**
 * What a failover group does after one of its members fails (SPEC 10.1.2).
 *
 * Kept next to [EngineError] rather than beside the code that runs a group,
 * because the question it answers is about the error and not about the group:
 * adding an [EngineError] variant and forgetting to classify it here is the
 * mistake this proximity is meant to prevent.
 */
enum class FailoverDecision {
    /**
     * Try the next member.
     *
     * The failure says something about *this* server or *this* path to it, and
     * a different server may well answer.
     */
    ADVANCE,

    /**
     * Stop, and report the failure.
     *
     * The failure says something about the request itself, and every remaining
     * member would fail the same way. The SPEC names one such case explicitly —
     * wrong credentials, where walking the list spreads failed logins across
     * servers and can lock the account out — and the same reasoning covers the
     * rest of this branch.
     */
    STOP,
}

/**
 * Whether a group should try its next member after this failure.
 *
 * The SPEC lists three errors that advance ([EngineError.NetworkUnreachable],
 * [EngineError.TimedOut], [EngineError.IpsecFailed]) and one that stops
 * ([EngineError.AuthenticationFailed]). The others it does not mention, and
 * they are classified here on the same principle rather than left to a
 * catch-all: an error about *reaching* a server advances, an error about what
 * the user configured or typed stops.
 *
 * Certificate failures are the interesting ones, and they stop. A group whose
 * members are the same organisation's servers will reject the same certificate
 * everywhere; a group whose members differ would hide "your pinned fingerprint
 * is stale" behind a connection that silently went somewhere else. Trust is not
 * something to fail over.
 */
val EngineError.failoverDecision: FailoverDecision
    get() = when (this) {
        // Named by SPEC 10.1.2: the server or the path to it is the problem.
        is EngineError.NetworkUnreachable -> FailoverDecision.ADVANCE
        is EngineError.TimedOut -> FailoverDecision.ADVANCE
        is EngineError.IpsecFailed -> FailoverDecision.ADVANCE

        // The transport reached a server and the conversation broke down there.
        // Another member is a different conversation, so it is worth having.
        is EngineError.TlsHandshakeFailed -> FailoverDecision.ADVANCE
        is EngineError.PppNegotiationFailed -> FailoverDecision.ADVANCE

        // Named by SPEC 10.1.2: the credentials are wrong everywhere.
        is EngineError.AuthenticationFailed -> FailoverDecision.STOP

        // Trust: see the note above. Failing over past a rejected certificate
        // is the one behaviour a VPN client must not have.
        is EngineError.CertificateRejected -> FailoverDecision.STOP
        is EngineError.CertificateExpired -> FailoverDecision.STOP
        is EngineError.HostnameMismatch -> FailoverDecision.STOP

        // A bug in this application. The next member would hit it too, and
        // moving on would bury it.
        is EngineError.Internal -> FailoverDecision.STOP
    }
