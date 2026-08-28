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
package io.github.mr1ve3r.combined.engine.sstp.client.ppp.auth

import io.github.mr1ve3r.combined.engine.sstp.ControlMessage
import io.github.mr1ve3r.combined.engine.sstp.Result
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PAP_CODE_AUTHENTICATE_ACK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PAP_CODE_AUTHENTICATE_NAK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapAuthenticateRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PapClient(private val bridge: SstpBridge) {
    internal val mailbox = Channel<PapFrame>(Channel.BUFFERED)
    private var jobAuth: Job? = null

    internal fun launchJobAuth() {
        jobAuth = bridge.scope.launch {
            val currentID = bridge.state.allocateNewFrameId()

            sendPAPRequest(currentID)

            while (isActive) {
                val received = mailbox.receive()

                if (received.id != currentID) continue

                when (received.code) {
                    PAP_CODE_AUTHENTICATE_ACK -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.PAP, Result.PROCEEDED),
                        )
                    }

                    PAP_CODE_AUTHENTICATE_NAK -> {
                        bridge.mailbox.send(
                            ControlMessage(Where.PAP, Result.ERR_AUTHENTICATION_FAILED),
                        )
                    }
                }
            }
        }
    }

    private suspend fun sendPAPRequest(id: Byte) {
        PapAuthenticateRequest().also {
            it.id = id
            it.idField = bridge.config.username.toByteArray(Charsets.US_ASCII)
            it.passwordField = bridge.config.password.toByteArray(Charsets.US_ASCII)

            bridge.send(it.toByteBuffer())
        }
    }

    internal fun cancel() {
        jobAuth?.cancel()
        mailbox.close()
    }
}
