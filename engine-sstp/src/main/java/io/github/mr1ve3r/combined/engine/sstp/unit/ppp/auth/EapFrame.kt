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

import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Frame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_EAP
import java.nio.ByteBuffer

internal const val EAP_CODE_REQUEST: Byte = 1
internal const val EAP_CODE_RESPONSE: Byte = 2
internal const val EAP_CODE_SUCCESS: Byte = 3
internal const val EAP_CODE_FAILURE: Byte = 4

internal const val EAP_TYPE_IDENTITY: Byte = 1
internal const val EAP_TYPE_NOTIFICATION: Byte = 2
internal const val EAP_TYPE_NAK: Byte = 3
internal const val EAP_TYPE_MS_AUTH: Byte = 26 // MSCHAPV2

internal abstract class EapFrame : Frame() {
    override val protocol = PPP_PROTOCOL_EAP
}

internal abstract class EapDataFrame : EapFrame() {
    override val length: Int
        get() = headerSize + 1 + typeData.size

    internal var type: Byte = 0
    internal var typeData = ByteArray(0)

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        type = buffer.get()
        typeData = ByteArray(givenLength - length)
        buffer.get(typeData)
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.put(type)
        buffer.put(typeData)
    }
}

internal class EapRequest : EapDataFrame() {
    override val code = EAP_CODE_REQUEST
}

internal class EapResponse : EapDataFrame() {
    override val code = EAP_CODE_RESPONSE
}

internal abstract class EapResultFrame : EapFrame() {
    override val length = headerSize

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)
    }
}

internal class EapSuccess : EapResultFrame() {
    override val code = EAP_CODE_SUCCESS
}

internal class EapFailure : EapResultFrame() {
    override val code = EAP_CODE_FAILURE
}
