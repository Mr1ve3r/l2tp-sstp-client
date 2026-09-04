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

import java.nio.ByteBuffer

/** Advances the position by [diff] bytes without reading them. */
internal fun ByteBuffer.move(diff: Int) {
    position(position() + diff)
}

/** Writes [size] zero bytes, for the reserved fields SSTP attributes carry. */
internal fun ByteBuffer.padZeroByte(size: Int) {
    repeat(size) { put(0) }
}

/** Reads the byte [diff] ahead of the position without consuming it. */
internal fun ByteBuffer.probeByte(diff: Int): Byte = this.get(this.position() + diff)

/** Reads the short [diff] ahead of the position without consuming it. */
internal fun ByteBuffer.probeShort(diff: Int): Short = this.getShort(this.position() + diff)

/** How much room is left after the limit, which is where more bytes can be read into. */
internal val ByteBuffer.capacityAfterLimit: Int
    get() = this.capacity() - this.limit()

/**
 * Moves the bytes between position and limit to the front of the buffer.
 *
 * This is what makes a partially consumed record readable again after more
 * bytes arrive: the remainder becomes the head of the buffer and the free space
 * ends up in one contiguous run at the back.
 */
internal fun ByteBuffer.slide() {
    val remaining = this.remaining()

    this.array().also {
        it.copyInto(it, 0, this.position(), this.limit())
    }

    this.position(0)
    this.limit(remaining)
}
