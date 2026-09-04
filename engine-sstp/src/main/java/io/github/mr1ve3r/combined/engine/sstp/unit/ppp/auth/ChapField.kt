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

import io.github.mr1ve3r.combined.engine.sstp.extension.toIntAsUByte
import io.github.mr1ve3r.combined.engine.sstp.unit.DataUnit
import java.nio.ByteBuffer

internal class ChapValueNameField : DataUnit() {
    internal var value = ByteArray(0)
    internal var name = ByteArray(0)
    internal var givenLength = 0 // must be given before reading
    override val length: Int
        get() = 1 + value.size + name.size

    override fun read(buffer: ByteBuffer) {
        value = ByteArray(buffer.get().toIntAsUByte())
        buffer.get(value)

        name = ByteArray(givenLength - length)
        buffer.get(name)
    }

    override fun write(buffer: ByteBuffer) {
        buffer.put(value.size.toByte())
        buffer.put(value)
        buffer.put(name)
    }
}

internal class ChapMessageField : DataUnit() {
    internal var message = ByteArray(0)
    internal var givenLength = 0 // must be given before reading
    override val length: Int
        get() = message.size

    override fun read(buffer: ByteBuffer) {
        message = ByteArray(givenLength)
        buffer.get(message)
    }

    override fun write(buffer: ByteBuffer) {
        buffer.put(message)
    }
}
