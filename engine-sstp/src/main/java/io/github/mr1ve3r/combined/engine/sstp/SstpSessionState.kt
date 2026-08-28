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

import io.github.mr1ve3r.combined.engine.PppAuthMethod
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the negotiation has agreed so far.
 *
 * The second third of upstream's `SharedBridge` (SPEC 6.3): everything that
 * changes while the clients talk to the server, and nothing that does not.
 * The engine owns one of these per connection and reads the result out of it
 * once IPCP is done.
 *
 * The fields are written by whichever client owns that step of the negotiation
 * and read by the engine afterwards, so they are marked `@Volatile` where a
 * plain read could otherwise see a stale value across dispatchers.
 *
 * @property nonce nonce the server sent in its Call Connect ACK, echoed back in
 *   the crypto binding.
 * @property hlak MSCHAPv2 master key material, used to compute the compound MAC.
 *   Never logged, never leaves this object.
 * @property guid correlation id this client presents in the HTTP layer.
 */
internal class SstpSessionState(initialMru: Int) {
    private val frameIdMutex = Mutex()
    private var frameId = -1

    @Volatile
    var currentMru: Int = initialMru

    @Volatile
    var currentAuth: PppAuthMethod? = null

    @Volatile
    var hashProtocol: Byte = 0

    @Volatile
    var hlak: ByteArray? = null

    val nonce: ByteArray = ByteArray(NONCE_SIZE)

    val currentIPv4: ByteArray = ByteArray(IPV4_SIZE)

    val currentIPv6: ByteArray = ByteArray(IPV6_IDENTIFIER_SIZE)

    val currentProposedDns: ByteArray = ByteArray(IPV4_SIZE)

    val guid: String = UUID.randomUUID().toString()

    /**
     * The next PPP frame identifier.
     *
     * One counter for the whole session across every sub-protocol, as upstream
     * has it: a peer that echoes an id back must be answering the request that
     * carried it, and reusing ids across LCP and IPCP would make that ambiguous.
     */
    suspend fun allocateNewFrameId(): Byte = frameIdMutex.withLock {
        frameId += 1
        frameId.toByte()
    }

    companion object {
        private const val NONCE_SIZE = 32
        private const val IPV4_SIZE = 4
        private const val IPV6_IDENTIFIER_SIZE = 8
    }
}
