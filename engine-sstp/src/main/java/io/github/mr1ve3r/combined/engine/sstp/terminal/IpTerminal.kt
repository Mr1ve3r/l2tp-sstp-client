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
package io.github.mr1ve3r.combined.engine.sstp.terminal

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * The TUN side of the tunnel: IP packets in, IP packets out.
 *
 * Upstream Open SSTP Client's `IPTerminal` did far more than this. It held a
 * live `VpnService.Builder` and called `addAddress`, `addDnsServer`, `addRoute`
 * and `addAllowedApplication` on it, so the address negotiated by IPCP, the
 * routing policy and the per-app rules were all decided inside the SSTP engine.
 *
 * Here the engine returns what IPCP agreed as
 * [TunnelParams][io.github.mr1ve3r.combined.engine.TunnelParams], the host
 * builds the interface through `core-tunnel`'s `TunnelBuilder` — the same one
 * L2TP goes through — and hands the descriptor back via
 * [VpnEngine.attachTun][io.github.mr1ve3r.combined.engine.VpnEngine.attachTun].
 * What is left is the two streams (PROVENANCE 3.3).
 *
 * @property mtu largest packet read in one go. The descriptor is blocking, so a
 *   read parks the retrieving coroutine until a packet arrives.
 * @property onClose releases whatever owns the descriptor.
 */
internal class IpTerminal(
    private val input: InputStream,
    private val output: OutputStream,
    private val mtu: Int,
    private val onClose: () -> Unit,
) {
    /** Writes [size] bytes starting at [start] of [buffer]'s array. The position is untouched. */
    fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        output.write(buffer.array(), start, size)
    }

    /** Reads one packet into [buffer], leaving it flipped and ready to send. */
    fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        buffer.position(input.read(buffer.array(), 0, mtu).coerceAtLeast(0))
        buffer.flip()
    }

    fun close() {
        onClose()
    }

    companion object {
        /**
         * Wraps the descriptor the host built from the negotiated parameters.
         *
         * The terminal takes ownership: closing it closes [fd].
         */
        fun of(fd: ParcelFileDescriptor, mtu: Int): IpTerminal = IpTerminal(
            input = FileInputStream(fd.fileDescriptor),
            output = FileOutputStream(fd.fileDescriptor),
            mtu = mtu,
            onClose = { fd.close() },
        )
    }
}
