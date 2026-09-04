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

import io.github.mr1ve3r.combined.engine.EngineError

/**
 * Turns a [ControlMessage] into the shared error vocabulary.
 *
 * Upstream Open SSTP Client showed the user `"MSCHAPV2: ERR_AUTHENTICATION_FAILED"`
 * and left them to work out what to do with it. The pair carries enough
 * information to say something better, and mapping it in one pure function
 * keeps the table auditable — the same table is written out in
 * `docs/ARCHITECTURE.md`.
 */
internal object SstpErrorMapping {
    /** @param message the failure a client reported. Must not be [Result.PROCEEDED]. */
    fun toEngineError(message: ControlMessage): EngineError {
        val detail = detailOf(message)

        return when (message.result) {
            Result.PROCEEDED -> EngineError.Internal(detail)

            Result.ERR_TIMEOUT -> EngineError.TimedOut(message.from.name.lowercase(), detail)

            Result.ERR_AUTHENTICATION_FAILED -> EngineError.AuthenticationFailed(detail)

            // The server's own response did not verify. Mutual authentication
            // failing is not "wrong password", but it is the same dead end for
            // the user, and calling it anything else would send them looking in
            // the wrong place.
            Result.ERR_VERIFICATION_FAILED -> when (message.from) {
                Where.MSCHAPV2, Where.CHAP, Where.EAP, Where.PAP -> EngineError.AuthenticationFailed(detail)
                Where.CERT, Where.CERT_PATH -> EngineError.CertificateRejected(null, detail)
                else -> EngineError.PppNegotiationFailed(message.from.name, detail)
            }

            Result.ERR_INVALID_PACKET_SIZE,
            Result.ERR_PARSING_FAILED,
            Result.ERR_UNKNOWN_TYPE,
            -> when (message.from) {
                Where.INCOMING, Where.OUTGOING, Where.ROUTE -> EngineError.Internal(detail)
                else -> EngineError.PppNegotiationFailed(message.from.name, detail)
            }

            else -> EngineError.PppNegotiationFailed(message.from.name, detail)
        }
    }

    private fun detailOf(message: ControlMessage): String {
        val header = "${message.from.name}: ${message.result.name}"
        return message.supplement?.takeIf { it.isNotBlank() }?.let { "$header\n$it" } ?: header
    }
}
