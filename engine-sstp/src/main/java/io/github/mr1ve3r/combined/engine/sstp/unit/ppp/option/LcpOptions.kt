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
package io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option

import io.github.mr1ve3r.combined.engine.sstp.debug.assertAlways
import io.github.mr1ve3r.combined.engine.sstp.extension.probeByte
import io.github.mr1ve3r.combined.engine.sstp.extension.toIntAsUShort
import java.nio.ByteBuffer

internal const val OPTION_TYPE_LCP_MRU: Byte = 1
internal const val OPTION_TYPE_LCP_AUTH: Byte = 3

internal const val CHAP_ALGORITHM_MSCHAPV2 = 0x81.toByte()

internal class MruOption : Option() {
    override val type = OPTION_TYPE_LCP_MRU
    override val length = headerSize + Short.SIZE_BYTES

    internal var unitSize = 0

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        unitSize = buffer.short.toIntAsUShort()
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.putShort(unitSize.toShort())
    }
}

internal class AuthOption : Option() {
    override val type = OPTION_TYPE_LCP_AUTH
    internal var protocol: Short = 0
    internal var holder = ByteArray(0)
    override val length: Int
        get() = headerSize + Short.SIZE_BYTES + holder.size

    override fun read(buffer: ByteBuffer) {
        readHeader(buffer)

        protocol = buffer.getShort()

        val holderSize = givenLength - length
        assertAlways(holderSize >= 0)

        if (holderSize > 0) {
            holder = ByteArray(holderSize).also { buffer.get(it) }
        }
    }

    override fun write(buffer: ByteBuffer) {
        writeHeader(buffer)

        buffer.putShort(protocol)
        buffer.put(holder)
    }
}

internal class LcpOptionPack(givenLength: Int = 0) : OptionPack(givenLength) {
    internal var mruOption: MruOption? = null
    internal var authOption: AuthOption? = null

    override val knownOptions: List<Option>
        get() = mutableListOf<Option>().also { options ->
            mruOption?.also { options.add(it) }
            authOption?.also { options.add(it) }
        }

    override fun retrieveOption(buffer: ByteBuffer): Option {
        val option = when (val type = buffer.probeByte(0)) {
            OPTION_TYPE_LCP_MRU -> MruOption().also { mruOption = it }
            OPTION_TYPE_LCP_AUTH -> AuthOption().also { authOption = it }
            else -> UnknownOption(type)
        }

        option.read(buffer)

        return option
    }
}
