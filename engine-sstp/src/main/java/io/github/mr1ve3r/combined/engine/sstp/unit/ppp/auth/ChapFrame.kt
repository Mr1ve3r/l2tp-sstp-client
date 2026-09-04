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
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_CHAP
import java.nio.ByteBuffer

internal const val CHAP_CODE_CHALLENGE: Byte = 1
internal const val CHAP_CODE_RESPONSE: Byte = 2
internal const val CHAP_CODE_SUCCESS: Byte = 3
internal const val CHAP_CODE_FAILURE: Byte = 4

internal abstract class ChapFrame : Frame() {
    override val protocol = PPP_PROTOCOL_CHAP
}

internal abstract class ChapValueNameFrame : ChapFrame() {
    internal var valueName = ChapValueNameField()
    override val length: Int
        get() = headerSize + valueName.length

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        valueName.givenLength = givenLength - headerSize
        valueName.read(buffer)
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        valueName.write(buffer)
    }
}

internal class ChapChallenge : ChapValueNameFrame() {
    override val code = CHAP_CODE_CHALLENGE
}

internal class ChapResponse : ChapValueNameFrame() {
    override val code = CHAP_CODE_RESPONSE
}

internal abstract class ChapMessageFrame : ChapFrame() {
    internal var message = ChapMessageField()
    override val length: Int
        get() = headerSize + message.length

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        message.givenLength = givenLength - headerSize
        message.read(buffer)
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        message.write(buffer)
    }
}

internal class ChapSuccess : ChapMessageFrame() {
    override val code = CHAP_CODE_SUCCESS
}

internal class ChapFailure : ChapMessageFrame() {
    override val code = CHAP_CODE_FAILURE
}
