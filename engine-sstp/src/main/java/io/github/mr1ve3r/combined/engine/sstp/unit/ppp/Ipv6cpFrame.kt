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

import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.Ipv6cpOptionPack
import java.nio.ByteBuffer

internal abstract class Ipv6cpFrame : Frame() {
    override val protocol = PPP_PROTOCOL_IPV6CP
}

internal abstract class Ipv6cpConfigureFrame : Ipv6cpFrame() {
    override val length: Int
        get() = headerSize + options.length

    internal var options: Ipv6cpOptionPack = Ipv6cpOptionPack()

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        options = Ipv6cpOptionPack(givenLength - length).also {
            it.read(buffer)
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        options.write(buffer)
    }
}

internal class Ipv6cpConfigureRequest : Ipv6cpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_REQUEST
}

internal class Ipv6cpConfigureAck : Ipv6cpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_ACK
}

internal class Ipv6cpConfigureNak : Ipv6cpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_NAK
}

internal class Ipv6cpConfigureReject : Ipv6cpConfigureFrame() {
    override val code = LCP_CODE_CONFIGURE_REJECT
}
