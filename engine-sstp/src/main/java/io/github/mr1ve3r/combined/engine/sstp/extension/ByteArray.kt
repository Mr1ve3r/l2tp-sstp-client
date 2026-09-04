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
package io.github.mr1ve3r.combined.engine.sstp.extension

/**
 * Uppercase hex, used for the `S=<digest>` authenticator MSCHAPv2 compares as
 * text.
 *
 * Never call this on key material that could reach a log: the engine's event
 * stream must not carry secrets (SPEC appendix A).
 */
internal fun ByteArray.toHexString(): String {
    val output = StringBuilder(size * 2)
    forEach { byte -> output.append(String.format("%02X", byte.toInt() and 0xFF)) }
    return output.toString()
}

/**
 * Parses hex text into bytes.
 *
 * Used for the MSCHAPv2 magic constants, which RFC 2759 states as literal byte
 * strings and which are kept in that form here so they can be compared with the
 * RFC by eye.
 */
internal fun String.toHexByteArray(): ByteArray {
    require(length % 2 == 0) { "A hex string needs an even number of characters" }

    val arrayLength = length / 2
    val output = ByteArray(arrayLength)

    repeat(arrayLength) {
        val start = it * 2
        output[it] = this.slice(start..start + 1).toInt(16).toByte()
    }

    return output
}
