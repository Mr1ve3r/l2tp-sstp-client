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
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.extension.move
import io.github.mr1ve3r.combined.engine.sstp.unit.DataUnit
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureNak
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Ipv6cpConfigureAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Ipv6cpConfigureNak
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Ipv6cpConfigureReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Ipv6cpConfigureRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_CODE_REJECT
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_CONFIGURE_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_CONFIGURE_NAK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_CONFIGURE_REJECT
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_CONFIGURE_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_DISCARD_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_ECHO_REPLY
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_ECHO_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_PROTOCOL_REJECT
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_TERMINATE_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LCP_CODE_TERMINATE_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpCodeReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureNak
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpDiscardRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpEchoReply
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpEchoRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpProtocolReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpTerminalAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpTerminalRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_CHALLENGE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_FAILURE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_RESPONSE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_SUCCESS
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapChallenge
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapFailure
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapResponse
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapSuccess
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_CODE_FAILURE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_CODE_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_CODE_RESPONSE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_CODE_SUCCESS
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapFailure
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapResponse
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapSuccess
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PAP_CODE_AUTHENTICATE_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PAP_CODE_AUTHENTICATE_NAK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PAP_CODE_AUTHENTICATE_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapAuthenticateAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapAuthenticateNak
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapAuthenticateRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_CONNECTED
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_CONNECT_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_CONNECT_NAK
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_CONNECT_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_ECHO_REQUEST
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_MESSAGE_TYPE_ECHO_RESPONSE
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

private suspend fun IncomingManager.tryReadDataUnit(unit: DataUnit, buffer: ByteBuffer): Exception? {
    try {
        unit.read(buffer)
    } catch (e: Exception) { // need to save packet log
        bridge.mailbox.send(
            ControlMessage(Where.INCOMING, Result.ERR_PARSING_FAILED),
        )

        return e
    }

    return null
}

internal suspend fun IncomingManager.processControlPacket(type: Short, buffer: ByteBuffer): Boolean {
    val packet = when (type) {
        SSTP_MESSAGE_TYPE_CALL_CONNECT_REQUEST -> SstpCallConnectRequest()
        SSTP_MESSAGE_TYPE_CALL_CONNECT_ACK -> SstpCallConnectAck()
        SSTP_MESSAGE_TYPE_CALL_CONNECT_NAK -> SstpCallConnectNak()
        SSTP_MESSAGE_TYPE_CALL_CONNECTED -> SstpCallConnected()
        SSTP_MESSAGE_TYPE_CALL_ABORT -> SstpCallAbort()
        SSTP_MESSAGE_TYPE_CALL_DISCONNECT -> SstpCallDisconnect()
        SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK -> SstpCallDisconnectAck()
        SSTP_MESSAGE_TYPE_ECHO_REQUEST -> SstpEchoRequest()
        SSTP_MESSAGE_TYPE_ECHO_RESPONSE -> SstpEchoResponse()
        else -> {
            bridge.mailbox.send(
                ControlMessage(Where.SSTP_CONTROL, Result.ERR_UNKNOWN_TYPE),
            )

            return false
        }
    }

    tryReadDataUnit(packet, buffer)?.also {
        return false
    }

    sstpMailbox?.send(packet)

    return true
}

internal suspend fun IncomingManager.processLcpFrame(code: Byte, buffer: ByteBuffer): Boolean {
    if (code in 1..4) {
        val configureFrame = when (code) {
            LCP_CODE_CONFIGURE_REQUEST -> LcpConfigureRequest()
            LCP_CODE_CONFIGURE_ACK -> LcpConfigureAck()
            LCP_CODE_CONFIGURE_NAK -> LcpConfigureNak()
            LCP_CODE_CONFIGURE_REJECT -> LcpConfigureReject()
            else -> throw NotImplementedError(code.toString())
        }

        tryReadDataUnit(configureFrame, buffer)?.also {
            return false
        }

        lcpMailbox?.send(configureFrame)
        return true
    }

    if (code in 5..11) {
        val frame = when (code) {
            LCP_CODE_TERMINATE_REQUEST -> LcpTerminalRequest()
            LCP_CODE_TERMINATE_ACK -> LcpTerminalAck()
            LCP_CODE_CODE_REJECT -> LcpCodeReject()
            LCP_CODE_PROTOCOL_REJECT -> LcpProtocolReject()
            LCP_CODE_ECHO_REQUEST -> LcpEchoRequest()
            LCP_CODE_ECHO_REPLY -> LcpEchoReply()
            LCP_CODE_DISCARD_REQUEST -> LcpDiscardRequest()
            else -> throw NotImplementedError(code.toString())
        }

        tryReadDataUnit(frame, buffer)?.also {
            return false
        }

        pppMailbox?.send(frame)
        return true
    }

    bridge.mailbox.send(
        ControlMessage(Where.LCP, Result.ERR_UNKNOWN_TYPE),
    )

    return false
}

internal suspend fun IncomingManager.processPAPFrame(code: Byte, buffer: ByteBuffer): Boolean {
    val frame = when (code) {
        PAP_CODE_AUTHENTICATE_REQUEST -> PapAuthenticateRequest()
        PAP_CODE_AUTHENTICATE_ACK -> PapAuthenticateAck()
        PAP_CODE_AUTHENTICATE_NAK -> PapAuthenticateNak()
        else -> {
            bridge.mailbox.send(
                ControlMessage(Where.PAP, Result.ERR_UNKNOWN_TYPE),
            )

            return false
        }
    }

    tryReadDataUnit(frame, buffer)?.also {
        return false
    }

    papMailbox?.send(frame)
    return true
}

internal suspend fun IncomingManager.processChapFrame(code: Byte, buffer: ByteBuffer): Boolean {
    val frame = when (code) {
        CHAP_CODE_CHALLENGE -> ChapChallenge()
        CHAP_CODE_RESPONSE -> ChapResponse()
        CHAP_CODE_SUCCESS -> ChapSuccess()
        CHAP_CODE_FAILURE -> ChapFailure()
        else -> {
            bridge.mailbox.send(
                ControlMessage(Where.CHAP, Result.ERR_UNKNOWN_TYPE),
            )

            return false
        }
    }

    tryReadDataUnit(frame, buffer)?.also {
        return false
    }

    chapMailbox?.send(frame)
    return true
}

internal suspend fun IncomingManager.processEAPFrame(code: Byte, buffer: ByteBuffer): Boolean {
    val frame = when (code) {
        EAP_CODE_REQUEST -> EapRequest()
        EAP_CODE_RESPONSE -> EapResponse()
        EAP_CODE_SUCCESS -> EapSuccess()
        EAP_CODE_FAILURE -> EapFailure()
        else -> {
            bridge.mailbox.send(
                ControlMessage(Where.EAP, Result.ERR_UNKNOWN_TYPE),
            )

            return false
        }
    }

    tryReadDataUnit(frame, buffer)?.also {
        return false
    }

    eapMailbox?.send(frame)
    return true
}

internal suspend fun IncomingManager.processIpcpFrame(code: Byte, buffer: ByteBuffer): Boolean {
    val frame = when (code) {
        LCP_CODE_CONFIGURE_REQUEST -> IpcpConfigureRequest()
        LCP_CODE_CONFIGURE_ACK -> IpcpConfigureAck()
        LCP_CODE_CONFIGURE_NAK -> IpcpConfigureNak()
        LCP_CODE_CONFIGURE_REJECT -> IpcpConfigureReject()
        else -> {
            bridge.mailbox.send(
                ControlMessage(Where.IPCP, Result.ERR_UNKNOWN_TYPE),
            )

            return false
        }
    }

    tryReadDataUnit(frame, buffer)?.also {
        return false
    }

    ipcpMailbox?.send(frame)
    return true
}

internal suspend fun IncomingManager.processIpv6cpFrame(code: Byte, buffer: ByteBuffer): Boolean {
    val frame = when (code) {
        LCP_CODE_CONFIGURE_REQUEST -> Ipv6cpConfigureRequest()
        LCP_CODE_CONFIGURE_ACK -> Ipv6cpConfigureAck()
        LCP_CODE_CONFIGURE_NAK -> Ipv6cpConfigureNak()
        LCP_CODE_CONFIGURE_REJECT -> Ipv6cpConfigureReject()
        else -> {
            bridge.mailbox.send(
                ControlMessage(Where.IPV6CP, Result.ERR_UNKNOWN_TYPE),
            )

            return false
        }
    }

    tryReadDataUnit(frame, buffer)?.also {
        return false
    }

    ipv6cpMailbox?.send(frame)
    return true
}

internal suspend fun IncomingManager.processUnknownProtocol(protocol: Short, packetSize: Int, buffer: ByteBuffer): Boolean {
    LcpProtocolReject().also {
        it.rejectedProtocol = protocol
        it.id = bridge.state.allocateNewFrameId()
        val infoStart = buffer.position() + 8
        val infoStop = buffer.position() + packetSize
        it.holder = buffer.array().sliceArray(infoStart until infoStop)

        bridge.send(it.toByteBuffer())
    }

    buffer.move(packetSize)

    return true
}

internal fun IncomingManager.processIPPacket(isEnabledProtocol: Boolean, packetSize: Int, buffer: ByteBuffer) {
    if (isEnabledProtocol) {
        val start = buffer.position() + 8
        val ipPacketSize = packetSize - 8

        // Dropped rather than dereferenced while the TUN does not exist yet.
        // `connect()` returns as soon as IPCP is done and the host builds the
        // interface after that, so a server that starts sending immediately can
        // land packets in this window. Upstream could not reach it, because
        // there the interface was built inside the negotiation.
        bridge.ipTerminal?.writePacket(start, ipPacketSize, buffer)
    }

    buffer.move(packetSize)
}
