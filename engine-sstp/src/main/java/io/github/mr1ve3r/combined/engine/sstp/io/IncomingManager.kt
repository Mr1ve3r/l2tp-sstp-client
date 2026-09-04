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
package io.github.mr1ve3r.combined.engine.sstp.io

import io.github.mr1ve3r.combined.engine.sstp.ControlMessage
import io.github.mr1ve3r.combined.engine.sstp.Result
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.SstpEngineConfig
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.client.SstpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.IpcpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.Ipv6cpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.LcpClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.PppClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth.ChapClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth.EapClient
import io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth.PapClient
import io.github.mr1ve3r.combined.engine.sstp.extension.probeByte
import io.github.mr1ve3r.combined.engine.sstp.extension.probeShort
import io.github.mr1ve3r.combined.engine.sstp.extension.toIntAsUShort
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Frame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Ipv6cpConfigureFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpEchoRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_HDLC_HEADER
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_CHAP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_EAP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_IP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_IPCP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_IPV6
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_IPV6CP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_LCP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_PAP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.ControlPacket
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_PACKET_TYPE_CONTROL
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_PACKET_TYPE_DATA
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpEchoRequest
import java.nio.ByteBuffer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SSTP_ECHO_INTERVAL = 20_000L
private const val PPP_ECHO_INTERVAL = 20_000L

internal class IncomingManager(internal val bridge: SstpBridge) {
    private val bufferSize = bridge.requireTransport().applicationBufferSize + SstpEngineConfig.MAX_MRU + 8 // MAX_MRU + 8 for fragment

    private var jobMain: Job? = null

    internal var lcpMailbox: Channel<LcpConfigureFrame>? = null
    internal var papMailbox: Channel<PapFrame>? = null
    internal var chapMailbox: Channel<ChapFrame>? = null
    internal var eapMailbox: Channel<EapFrame>? = null
    internal var ipcpMailbox: Channel<IpcpConfigureFrame>? = null
    internal var ipv6cpMailbox: Channel<Ipv6cpConfigureFrame>? = null
    internal var pppMailbox: Channel<Frame>? = null
    internal var sstpMailbox: Channel<ControlPacket>? = null

    private val sstpTimer = EchoTimer(SSTP_ECHO_INTERVAL) {
        SstpEchoRequest().also {
            bridge.send(it.toByteBuffer())
        }
    }

    private val pppTimer = EchoTimer(PPP_ECHO_INTERVAL) {
        LcpEchoRequest().also {
            it.id = bridge.state.allocateNewFrameId()
            it.holder = "Abura Mashi Mashi".toByteArray(Charsets.US_ASCII)
            bridge.send(it.toByteBuffer())
        }
    }

    internal fun <T> registerMailbox(client: T) {
        when (client) {
            is LcpClient -> lcpMailbox = client.mailbox
            is PapClient -> papMailbox = client.mailbox
            is ChapClient -> chapMailbox = client.mailbox
            is EapClient -> eapMailbox = client.mailbox
            is IpcpClient -> ipcpMailbox = client.mailbox
            is Ipv6cpClient -> ipv6cpMailbox = client.mailbox
            is PppClient -> pppMailbox = client.mailbox
            is SstpClient -> sstpMailbox = client.mailbox
            else -> throw NotImplementedError(client?.toString() ?: "")
        }
    }

    internal fun <T> unregisterMailbox(client: T) {
        when (client) {
            is LcpClient -> lcpMailbox = null
            is PapClient -> papMailbox = null
            is ChapClient -> chapMailbox = null
            is EapClient -> eapMailbox = null
            is IpcpClient -> ipcpMailbox = null
            is Ipv6cpClient -> ipv6cpMailbox = null
            is PppClient -> pppMailbox = null
            is SstpClient -> sstpMailbox = null
            else -> throw NotImplementedError(client?.toString() ?: "")
        }
    }

    internal fun launchJobMain() {
        jobMain = bridge.scope.launch {
            val buffer = ByteBuffer.allocate(bufferSize).also { it.limit(0) }

            sstpTimer.tick()
            pppTimer.tick()

            while (isActive) {
                if (!sstpTimer.checkAlive()) {
                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_CONTROL, Result.ERR_TIMEOUT),
                    )

                    return@launch
                }

                if (!pppTimer.checkAlive()) {
                    bridge.mailbox.send(
                        ControlMessage(Where.PPP, Result.ERR_TIMEOUT),
                    )

                    return@launch
                }

                val size = getPacketSize(buffer)
                when (size) {
                    in 4..bufferSize -> { }

                    -1 -> {
                        bridge.requireTransport().receive(buffer)
                        continue
                    }

                    else -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.INCOMING, Result.ERR_INVALID_PACKET_SIZE),
                        )
                        return@launch
                    }
                }

                if (size > buffer.remaining()) {
                    bridge.requireTransport().receive(buffer)
                    continue
                }

                sstpTimer.tick()

                when (buffer.probeShort(0)) {
                    SSTP_PACKET_TYPE_DATA -> {
                        if (buffer.probeShort(4) != PPP_HDLC_HEADER) {
                            bridge.mailbox.send(
                                ControlMessage(Where.SSTP_DATA, Result.ERR_UNKNOWN_TYPE),
                            )
                            return@launch
                        }

                        pppTimer.tick()

                        val protocol = buffer.probeShort(6)

                        // DATA
                        if (protocol == PPP_PROTOCOL_IP) {
                            processIPPacket(bridge.config.ipv4Enabled, size, buffer)
                            continue
                        }

                        if (protocol == PPP_PROTOCOL_IPV6) {
                            processIPPacket(bridge.config.ipv6Enabled, size, buffer)
                            continue
                        }

                        // CONTROL
                        val code = buffer.probeByte(8)
                        val isGo = when (protocol) {
                            PPP_PROTOCOL_LCP -> processLcpFrame(code, buffer)
                            PPP_PROTOCOL_PAP -> processPAPFrame(code, buffer)
                            PPP_PROTOCOL_CHAP -> processChapFrame(code, buffer)
                            PPP_PROTOCOL_EAP -> processEAPFrame(code, buffer)
                            PPP_PROTOCOL_IPCP -> processIpcpFrame(code, buffer)
                            PPP_PROTOCOL_IPV6CP -> processIpv6cpFrame(code, buffer)
                            else -> processUnknownProtocol(protocol, size, buffer)
                        }

                        if (!isGo) {
                            return@launch
                        }
                    }

                    SSTP_PACKET_TYPE_CONTROL -> {
                        if (!processControlPacket(buffer.probeShort(4), buffer)) {
                            return@launch
                        }
                    }

                    else -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.INCOMING, Result.ERR_UNKNOWN_TYPE),
                        )

                        return@launch
                    }
                }
            }
        }
    }

    private fun getPacketSize(buffer: ByteBuffer): Int {
        return if (buffer.remaining() < 4) {
            -1
        } else {
            buffer.probeShort(2).toIntAsUShort()
        }
    }

    internal fun cancel() {
        jobMain?.cancel()
    }
}
