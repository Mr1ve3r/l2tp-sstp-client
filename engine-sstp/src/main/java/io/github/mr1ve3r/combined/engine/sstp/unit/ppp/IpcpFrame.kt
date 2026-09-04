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

import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.IpcpOptionPack
import java.nio.ByteBuffer

internal abstract class IpcpFrame : Frame() {
    override val protocol = PPP_PROTOCOL_IPCP
}

internal abstract class IpcpConfigureFrame : IpcpFrame() {
    override val length: Int
        get() = headerSize + options.length

    internal var options: IpcpOptionPack = IpcpOptionPack()

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        options = IpcpOptionPack(givenLength - length).also {
            it.read(buffer)
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        options.write(buffer)
    }
}

internal class IpcpConfigureRequest : IpcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_REQUEST
}

internal class IpcpConfigureAck : IpcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_ACK
}

internal class IpcpConfigureNak : IpcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_NAK
}

internal class IpcpConfigureReject : IpcpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_REJECT
}
