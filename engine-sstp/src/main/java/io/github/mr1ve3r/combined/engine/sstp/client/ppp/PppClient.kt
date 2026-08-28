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
package io.github.mr1ve3r.combined.engine.sstp.client.ppp

import io.github.mr1ve3r.combined.engine.sstp.ControlMessage
import io.github.mr1ve3r.combined.engine.sstp.Result
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.Frame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpCodeReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpDiscardRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpEchoReply
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpEchoRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpProtocolReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpTerminalAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpTerminalRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PppClient(val bridge: SstpBridge) {
    internal val mailbox = Channel<Frame>(Channel.BUFFERED)

    private var jobControl: Job? = null

    internal fun launchJobControl() {
        jobControl = bridge.scope.launch {
            while (isActive) {
                when (val received = mailbox.receive()) {
                    is LcpEchoRequest -> {
                        LcpEchoReply().also {
                            it.id = received.id
                            it.holder = "Abura Mashi Mashi".toByteArray(Charsets.US_ASCII)
                            bridge.send(it.toByteBuffer())
                        }
                    }

                    is LcpEchoReply -> { }

                    is LcpDiscardRequest -> { }

                    is LcpTerminalRequest -> {
                        LcpTerminalAck().also {
                            it.id = received.id
                            bridge.send(it.toByteBuffer())
                        }

                        bridge.mailbox.send(
                            ControlMessage(Where.PPP, Result.ERR_TERMINATE_REQUESTED),
                        )
                    }

                    is LcpProtocolReject -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.PPP, Result.ERR_PROTOCOL_REJECTED),
                        )
                    }

                    is LcpCodeReject -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.PPP, Result.ERR_CODE_REJECTED),
                        )
                    }

                    else -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.PPP, Result.ERR_UNEXPECTED_MESSAGE),
                        )
                    }
                }
            }
        }
    }

    internal fun cancel() {
        jobControl?.cancel()
        mailbox.close()
    }
}
