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
package io.github.mr1ve3r.combined.engine.sstp.client

import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.sstp.ControlMessage
import io.github.mr1ve3r.combined.engine.sstp.Result
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.CERT_HASH_PROTOCOL_SHA1
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.CERT_HASH_PROTOCOL_SHA256
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.ControlPacket
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallAbort
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallConnectAck
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallConnectNak
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallConnectRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallConnected
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallDisconnect
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallDisconnectAck
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpEchoRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpEchoResponse
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SSTP_REQUEST_INTERVAL = 60_000L
private const val SSTP_REQUEST_COUNT = 3
internal const val SSTP_REQUEST_TIMEOUT = SSTP_REQUEST_INTERVAL * SSTP_REQUEST_COUNT

/**
 * How long to wait for an authentication method to finish.
 *
 * Upstream made this a preference. One number is enough: everything here is
 * one round trip against a server that has already completed a TLS handshake,
 * so a minute means the server has stopped answering.
 */
internal const val PPP_AUTH_TIMEOUT = 60_000L

private class HashSetting(hashProtocol: Byte) {
    val cmacSize: Short // little endian
    val digestProtocol: String
    val macProtocol: String

    init {
        when (hashProtocol) {
            CERT_HASH_PROTOCOL_SHA1 -> {
                cmacSize = 0x1400.toShort()
                digestProtocol = "SHA-1"
                macProtocol = "HmacSHA1"
            }

            CERT_HASH_PROTOCOL_SHA256 -> {
                cmacSize = 0x2000.toShort()
                digestProtocol = "SHA-256"
                macProtocol = "HmacSHA256"
            }

            else -> throw NotImplementedError(hashProtocol.toString())
        }
    }
}

internal class SstpClient(val bridge: SstpBridge) {
    internal val mailbox = Channel<ControlPacket>(Channel.BUFFERED)

    private var jobRequest: Job? = null
    private var jobControl: Job? = null

    internal fun launchJobControl() {
        jobControl = bridge.scope.launch {
            while (isActive) {
                when (mailbox.receive()) {
                    is SstpEchoRequest -> {
                        SstpEchoResponse().also {
                            bridge.send(it.toByteBuffer())
                        }
                    }

                    is SstpEchoResponse -> { }

                    is SstpCallDisconnect -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.SSTP_CONTROL, Result.ERR_DISCONNECT_REQUESTED),
                        )
                    }

                    is SstpCallAbort -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.SSTP_CONTROL, Result.ERR_ABORT_REQUESTED),
                        )
                    }

                    else -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.SSTP_CONTROL, Result.ERR_UNEXPECTED_MESSAGE),
                        )
                    }
                }
            }
        }
    }

    internal fun launchJobRequest() {
        jobRequest = bridge.scope.launch {
            val request = SstpCallConnectRequest()
            var requestCount = SSTP_REQUEST_COUNT

            val received: ControlPacket
            while (true) {
                requestCount--
                if (requestCount < 0) {
                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_REQUEST, Result.ERR_COUNT_EXHAUSTED),
                    )

                    return@launch
                }

                bridge.send(request.toByteBuffer())

                received = withTimeoutOrNull(SSTP_REQUEST_INTERVAL) { mailbox.receive() } ?: continue

                break
            }

            when (received) {
                is SstpCallConnectAck -> {
                    bridge.state.hashProtocol = when (received.request.bitmask.toInt()) {
                        in 2..3 -> CERT_HASH_PROTOCOL_SHA256
                        1 -> CERT_HASH_PROTOCOL_SHA1
                        else -> {
                            bridge.mailbox.send(
                                ControlMessage(Where.SSTP_HASH, Result.ERR_UNKNOWN_TYPE),
                            )

                            return@launch
                        }
                    }

                    received.request.nonce.copyInto(bridge.state.nonce)

                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_REQUEST, Result.PROCEEDED),
                    )
                }

                is SstpCallConnectNak -> {
                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_REQUEST, Result.ERR_NEGATIVE_ACKNOWLEDGED),
                    )
                }

                is SstpCallDisconnect -> {
                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_REQUEST, Result.ERR_DISCONNECT_REQUESTED),
                    )
                }

                is SstpCallAbort -> {
                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_REQUEST, Result.ERR_ABORT_REQUESTED),
                    )
                }

                else -> {
                    bridge.mailbox.send(
                        ControlMessage(Where.SSTP_REQUEST, Result.ERR_UNEXPECTED_MESSAGE),
                    )
                }
            }
        }
    }

    internal suspend fun sendCallConnected() {
        val call = SstpCallConnected()

        val cmkInputBuffer = ByteBuffer.allocate(32)
        val cmacInputBuffer = ByteBuffer.allocate(call.length)
        val hashSetting = HashSetting(bridge.state.hashProtocol)

        bridge.state.nonce.copyInto(call.binding.nonce)
        MessageDigest.getInstance(hashSetting.digestProtocol).also {
            it.digest(bridge.requireTransport().serverCertificate).copyInto(call.binding.certHash)
        }

        call.binding.hashProtocol = bridge.state.hashProtocol
        call.write(cmacInputBuffer)

        val hlak = when (bridge.state.currentAuth) {
            PppAuthMethod.PAP -> ByteArray(32)
            PppAuthMethod.MSCHAPV2, PppAuthMethod.EAP_MSCHAPV2 -> bridge.state.hlak!!
            // The engine only reaches the crypto binding after an
            // authentication method has succeeded, so this cannot be null here.
            else -> error("The compound MAC needs a completed authentication")
        }

        val cmkSeed = "SSTP inner method derived CMK".toByteArray(Charset.forName("US-ASCII"))
        cmkInputBuffer.put(cmkSeed)
        cmkInputBuffer.putShort(hashSetting.cmacSize)
        cmkInputBuffer.put(1)

        Mac.getInstance(hashSetting.macProtocol).also {
            it.init(SecretKeySpec(hlak, hashSetting.macProtocol))
            val cmk = it.doFinal(cmkInputBuffer.array())
            it.init(SecretKeySpec(cmk, hashSetting.macProtocol))
            val cmac = it.doFinal(cmacInputBuffer.array())
            cmac.copyInto(call.binding.compoundMac)
        }

        bridge.send(call.toByteBuffer())
    }

    internal suspend fun sendLastPacket(type: Short) {
        val packet = when (type) {
            SSTP_MESSAGE_TYPE_CALL_DISCONNECT -> SstpCallDisconnect()
            SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK -> SstpCallDisconnectAck()
            SSTP_MESSAGE_TYPE_CALL_ABORT -> SstpCallAbort()

            else -> throw NotImplementedError(type.toString())
        }

        try { // maybe the socket is no longer available
            bridge.send(packet.toByteBuffer())
        } catch (_: Throwable) { }
    }

    internal fun cancel() {
        jobRequest?.cancel()
        jobControl?.cancel()
        mailbox.close()
    }
}
