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
package io.github.mr1ve3r.combined.engine.sstp.unit.ppp

import io.github.mr1ve3r.combined.engine.sstp.debug.assertAlways
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.LcpOptionPack
import java.nio.ByteBuffer

internal const val LCP_CODE_CONFIGURE_REQUEST: Byte = 1
internal const val LCP_CODE_CONFIGURE_ACK: Byte = 2
internal const val LCP_CODE_CONFIGURE_NAK: Byte = 3
internal const val LCP_CODE_CONFIGURE_REJECT: Byte = 4
internal const val LCP_CODE_TERMINATE_REQUEST: Byte = 5
internal const val LCP_CODE_TERMINATE_ACK: Byte = 6
internal const val LCP_CODE_CODE_REJECT: Byte = 7
internal const val LCP_CODE_PROTOCOL_REJECT: Byte = 8
internal const val LCP_CODE_ECHO_REQUEST: Byte = 9
internal const val LCP_CODE_ECHO_REPLY: Byte = 10
internal const val LCP_CODE_DISCARD_REQUEST: Byte = 11

internal abstract class LcpFrame : Frame() {
    override val protocol = PPP_PROTOCOL_LCP
}

internal abstract class LcpConfigureFrame : LcpFrame() {
    override val length: Int
        get() = headerSize + options.length

    internal var options: LcpOptionPack = LcpOptionPack()

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        options = LcpOptionPack(givenLength - length).also {
            it.read(buffer)
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        options.write(buffer)
    }
}

internal class LcpConfigureRequest : LcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_REQUEST
}

internal class LcpConfigureAck : LcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_ACK
}

internal class LcpConfigureNak : LcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_NAK
}

internal class LcpConfigureReject : LcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_REJECT
}

internal abstract class LcpDataHoldingFrame : LcpFrame() {
    override val length: Int
        get() = headerSize + holder.size

    internal var holder = ByteArray(0)

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        val holderSize = givenLength - length
        assertAlways(holderSize >= 0)

        if (holderSize > 0) {
            holder = ByteArray(holderSize).also { buffer.get(it) }
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.put(holder)
    }
}

internal class LcpTerminalRequest : LcpDataHoldingFrame() {
    override val code = LCP_CODE_TERMINATE_REQUEST
}

internal class LcpTerminalAck : LcpDataHoldingFrame() {
    override val code = LCP_CODE_TERMINATE_ACK
}

internal class LcpCodeReject : LcpDataHoldingFrame() {
    override val code = LCP_CODE_CODE_REJECT
}

internal class LcpProtocolReject : LcpFrame() {
    override val code = LCP_CODE_PROTOCOL_REJECT
    override val length: Int
        get() = headerSize + Short.SIZE_BYTES + holder.size

    internal var rejectedProtocol: Short = 0

    internal var holder = ByteArray(0)

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        rejectedProtocol = buffer.short

        val holderSize = givenLength - length
        assertAlways(holderSize >= 0)

        if (holderSize > 0) {
            holder = ByteArray(holderSize).also { buffer.get(it) }
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.putShort(rejectedProtocol)
        buffer.put(holder)
    }
}

internal abstract class LcpMagicNumberFrame : LcpFrame() {
    override val length: Int
        get() = headerSize + Int.SIZE_BYTES + holder.size

    internal var magicNumber = 0

    internal var holder = ByteArray(0)

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        magicNumber = buffer.int

        val holderSize = givenLength - length
        assertAlways(holderSize >= 0)

        if (holderSize > 0) {
            holder = ByteArray(holderSize).also { buffer.get(it) }
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.putInt(magicNumber)
        buffer.put(holder)
    }
}

internal class LcpEchoRequest : LcpMagicNumberFrame() {
    override val code = LCP_CODE_ECHO_REQUEST
}

internal class LcpEchoReply : LcpMagicNumberFrame() {
    override val code = LCP_CODE_ECHO_REPLY
}

internal class LcpDiscardRequest : LcpMagicNumberFrame() {
    override val code = LCP_CODE_DISCARD_REQUEST
}
