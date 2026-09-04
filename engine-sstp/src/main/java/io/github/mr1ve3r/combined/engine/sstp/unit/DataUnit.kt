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
package io.github.mr1ve3r.combined.engine.sstp.unit

import java.nio.ByteBuffer

/**
 * A structure that can be read from and written to the wire.
 *
 * Every SSTP packet, SSTP attribute, PPP frame and PPP option in this engine is
 * one of these. [length] is what the unit *will* occupy once written, computed
 * from its current contents; the length the peer claimed is kept separately by
 * each implementation as `givenLength`, so the two can be compared instead of
 * trusted.
 */
internal abstract class DataUnit {
    internal abstract val length: Int

    internal abstract fun write(buffer: ByteBuffer)

    internal abstract fun read(buffer: ByteBuffer)

    /** Serialises this unit into a buffer of exactly [length] bytes, ready to send. */
    internal fun toByteBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocate(length)

        write(buffer)
        buffer.flip()

        return buffer
    }
}
