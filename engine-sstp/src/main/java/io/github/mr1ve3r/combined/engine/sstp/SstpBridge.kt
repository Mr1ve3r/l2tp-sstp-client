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
package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.engine.sstp.terminal.IpTerminal
import io.github.mr1ve3r.combined.engine.sstp.unit.DataUnit
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope

/**
 * The wiring every client needs, and nothing else.
 *
 * Upstream Open SSTP Client's `SharedBridge` was a god-object: preferences, a
 * live `VpnService.Builder`, both terminals, the whole of the PPP negotiation
 * state, the control channel and the per-app list, with every client reaching
 * into it for whatever it wanted. It has been cut into
 * [SstpEngineConfig] (read-only settings), [SstpSessionState] (what the
 * negotiation has agreed) and [ControlMailbox] (progress and failure), and what
 * remains here is a holder that hands a client those three plus the scope it
 * runs in (SPEC 6.3).
 *
 * **There is deliberately no `VpnService.Builder` anywhere below this class.**
 * An engine that can configure the interface itself competes with the host for
 * the one `VpnService` Android allows, and phase 7 would not compose. The TUN
 * arrives as a file descriptor through
 * [VpnEngine.attachTun][io.github.mr1ve3r.combined.engine.VpnEngine.attachTun]
 * and routing policy lives in `core-tunnel`, shared with L2TP.
 *
 * @property scope the engine's own scope. Cancelling it stops every client.
 */
internal class SstpBridge(
    val config: SstpEngineConfig,
    val state: SstpSessionState,
    val mailbox: ControlMailbox,
    val scope: CoroutineScope,
) {
    /** The TLS transport, once the SSL terminal has one. */
    @Volatile
    var transport: SstpTransport? = null

    /** The TUN side, once the host has handed the descriptor back. */
    @Volatile
    var ipTerminal: IpTerminal? = null

    /**
     * The transport, which must exist by the time any client runs.
     *
     * Every client is started by the engine after the terminal is up, so a
     * missing transport is a wiring bug rather than a connection failure.
     */
    fun requireTransport(): SstpTransport = transport ?: error("The SSTP transport is not established yet")

    /** Sends an already-serialised buffer. */
    suspend fun send(buffer: ByteBuffer) {
        requireTransport().send(buffer)
    }

    /** Serialises [unit] and sends it. */
    suspend fun send(unit: DataUnit) {
        requireTransport().send(unit.toByteBuffer())
    }
}

/**
 * The TLS stream, as the protocol code sees it.
 *
 * An interface rather than the terminal itself so the clients and the incoming
 * pipeline can be exercised without a socket — upstream they could not be,
 * which is why none of them had a test.
 */
internal interface SstpTransport {
    /** Largest plaintext record the TLS engine will produce, and the size the buffers are cut for. */
    val applicationBufferSize: Int

    /** The leaf certificate the server presented, DER-encoded, for the crypto binding hash. */
    val serverCertificate: ByteArray

    /** Encrypts and writes everything remaining in [buffer]. Serialised against other senders. */
    suspend fun send(buffer: ByteBuffer)

    /** Reads and decrypts more bytes into [buffer], appending after its limit. */
    fun receive(buffer: ByteBuffer)
}
