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
package io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth

import io.github.mr1ve3r.combined.engine.sstp.debug.ParsingDataUnitException
import io.github.mr1ve3r.combined.engine.sstp.debug.assertAlways
import io.github.mr1ve3r.combined.engine.sstp.extension.toIntAsUByte
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Frame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_PAP
import java.nio.ByteBuffer

internal const val PAP_CODE_AUTHENTICATE_REQUEST: Byte = 1
internal const val PAP_CODE_AUTHENTICATE_ACK: Byte = 2
internal const val PAP_CODE_AUTHENTICATE_NAK: Byte = 3

internal abstract class PapFrame : Frame() {
    override val protocol = PPP_PROTOCOL_PAP
}

internal class PapAuthenticateRequest : PapFrame() {
    override val code = PAP_CODE_AUTHENTICATE_REQUEST
    override val length: Int
        get() = headerSize + 1 + idField.size + 1 + passwordField.size

    internal var idField = ByteArray(0)
    internal var passwordField = ByteArray(0)

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        val idLength = buffer.get().toIntAsUByte()
        idField = ByteArray(idLength).also { buffer.get(it) }

        val passwordLength = buffer.get().toIntAsUByte()
        passwordField = ByteArray(passwordLength).also { buffer.get(it) }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.put(idField.size.toByte())
        buffer.put(idField)
        buffer.put(passwordField.size.toByte())
        buffer.put(passwordField)
    }
}

internal abstract class PapAuthenticateAcknowledgement : PapFrame() {
    override val length: Int
        get() = headerSize + (if (message.isEmpty()) 0 else message.size + 1)

    private var message = ByteArray(0)

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        // Upstream reads `length - headerSize` here, which is computed from the
        // still-empty message and is therefore always zero: the message was
        // never read, and worse, its bytes were left in the buffer for the next
        // packet boundary to trip over. The claimed length is what this has to
        // be measured against.
        when (val remaining = givenLength - headerSize) {
            0 -> {}
            in 1..Int.MAX_VALUE -> {
                val messageLength = buffer.get().toIntAsUByte()
                assertAlways(messageLength == remaining - 1)
                message = ByteArray(messageLength).also { buffer.get(it) }
            }

            else -> throw ParsingDataUnitException()
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        if (message.isNotEmpty()) {
            buffer.put(message.size.toByte())
            buffer.put(message)
        }
    }
}

internal class PapAuthenticateAck : PapAuthenticateAcknowledgement() {
    override val code = PAP_CODE_AUTHENTICATE_ACK
}

internal class PapAuthenticateNak : PapAuthenticateAcknowledgement() {
    override val code = PAP_CODE_AUTHENTICATE_NAK
}
