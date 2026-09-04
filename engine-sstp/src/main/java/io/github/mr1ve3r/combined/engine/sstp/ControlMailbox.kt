/*
 * Derived from Open SSTP Client
 * https://github.com/kittoku/Open-SSTP-Client
 * Copyright (c) 2019 KOBAYASHI Ittoku
 * Licensed under the MIT License.
 * See third_party/open-sstp-client/LICENSE for the full text.
 *
 * Modifications Copyright (C) 2026 Mr1ve3r
 * Licensed under GPL-3.0-or-later as part of this project.
 */
package io.github.mr1ve3r.combined.engine.sstp

import kotlinx.coroutines.channels.Channel

/**
 * The single channel every part of the engine reports progress and failure on.
 *
 * This is one third of what upstream Open SSTP Client kept in `SharedBridge`
 * (SPEC 6.3). [Where] and [Result] come across unchanged: their granularity is
 * good, and the pair maps cleanly onto
 * [EngineError][io.github.mr1ve3r.combined.engine.EngineError] — the table is
 * in `docs/ARCHITECTURE.md`.
 *
 * Each client sends exactly one message per step it is responsible for, and the
 * engine's `expectProceeded` loop consumes them in order.
 */
internal class ControlMailbox {
    private val channel = Channel<ControlMessage>(Channel.BUFFERED)

    /** Reports the outcome of a step. Suspends only if the buffer is full. */
    internal suspend fun send(from: Where, result: Result, supplement: String? = null) {
        channel.send(ControlMessage(from, result, supplement))
    }

    /** Reports an already-built message. */
    internal suspend fun send(message: ControlMessage) {
        channel.send(message)
    }

    /**
     * Reports a failure from a place that cannot suspend.
     *
     * The coroutine exception handler is the caller: it runs when a client has
     * already crashed, and a handler that suspends on a full buffer would
     * simply lose the reason. Dropped if the buffer really is full, in which
     * case an earlier failure is already on its way to the engine.
     */
    internal fun tryReport(from: Where, result: Result, supplement: String? = null) {
        channel.trySend(ControlMessage(from, result, supplement))
    }

    /** Waits for the next message. */
    internal suspend fun receive(): ControlMessage = channel.receive()

    /** Releases anything blocked on the channel when the engine shuts down. */
    internal fun close() {
        channel.close()
    }
}

/**
 * One report from one step of the connection.
 *
 * @property from the component that produced it.
 * @property result what happened.
 * @property supplement technical detail for the log. Never a secret: it is
 *   copied into [EngineError.detail][io.github.mr1ve3r.combined.engine.EngineError.detail]
 *   and from there into an exportable log.
 */
internal data class ControlMessage(
    val from: Where,
    val result: Result,
    val supplement: String? = null,
)

/** The component a [ControlMessage] came from. Imported unchanged from upstream. */
internal enum class Where {
    CERT,
    CERT_PATH,
    SSL,
    PROXY,
    SSTP_DATA,
    SSTP_CONTROL,
    SSTP_REQUEST,
    SSTP_HASH,
    PPP,
    PAP,
    CHAP,
    MSCHAPV2,
    EAP,
    LCP,
    LCP_MRU,
    LCP_AUTH,
    IPCP,
    IPCP_IP,
    IPV6CP,
    IPV6CP_IDENTIFIER,
    IP,
    IPV4,
    IPV6,
    ROUTE,
    INCOMING,
    OUTGOING,
}

/** What happened at a [Where]. Imported unchanged from upstream. */
internal enum class Result {
    PROCEEDED,

    // common errors
    ERR_TIMEOUT,
    ERR_COUNT_EXHAUSTED,
    ERR_UNKNOWN_TYPE, // the data cannot be parsed
    ERR_UNEXPECTED_MESSAGE, // the data can be parsed, but it arrived at the wrong time
    ERR_PARSING_FAILED,
    ERR_VERIFICATION_FAILED,

    // for SSTP
    ERR_NEGATIVE_ACKNOWLEDGED,
    ERR_ABORT_REQUESTED,
    ERR_DISCONNECT_REQUESTED,

    // for PPP
    ERR_TERMINATE_REQUESTED,
    ERR_PROTOCOL_REJECTED,
    ERR_CODE_REJECTED,
    ERR_AUTHENTICATION_FAILED,
    ERR_ADDRESS_REJECTED,
    ERR_OPTION_REJECTED,

    // for IP
    ERR_INVALID_ADDRESS,

    // for INCOMING
    ERR_INVALID_PACKET_SIZE,
}
