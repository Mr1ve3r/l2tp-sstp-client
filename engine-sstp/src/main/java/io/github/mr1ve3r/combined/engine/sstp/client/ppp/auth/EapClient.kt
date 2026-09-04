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
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_TYPE_IDENTITY
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_TYPE_NAK
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_TYPE_NOTIFICATION
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapFailure
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapResponse
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapResultFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapSuccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal abstract class EapClient(protected val bridge: SstpBridge) {
    protected abstract val algorithm: Byte
    internal val mailbox = Channel<EapFrame>(Channel.BUFFERED)
    protected var isResultAcceptable = false
    private var jobAuth: Job? = null

    internal fun launchJobAuth() {
        jobAuth = bridge.scope.launch {
            while (isActive) {
                when (val received = mailbox.receive()) {
                    is EapRequest -> {
                        when (received.type) {
                            EAP_TYPE_IDENTITY -> sendIdentity(received)

                            EAP_TYPE_NOTIFICATION, EAP_TYPE_NAK -> {}

                            algorithm -> responseRequest(received)

                            else -> sendNak(received)
                        }
                    }

                    is EapResultFrame -> {
                        if (isResultAcceptable) {
                            when (received) {
                                is EapSuccess -> {
                                    bridge.mailbox.send(
                                        ControlMessage(Where.EAP, Result.PROCEEDED),
                                    )
                                }

                                is EapFailure -> {
                                    bridge.mailbox.send(
                                        ControlMessage(Where.EAP, Result.ERR_AUTHENTICATION_FAILED),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun sendIdentity(serverIdentity: EapRequest) {
        EapResponse().also {
            it.id = serverIdentity.id
            it.type = EAP_TYPE_IDENTITY
            it.typeData = bridge.config.username.toByteArray(Charsets.US_ASCII)

            bridge.send(it.toByteBuffer())
        }
    }

    private suspend fun sendNak(serverRequest: EapRequest) {
        EapResponse().also {
            it.id = serverRequest.id
            it.type = EAP_TYPE_NAK
            it.typeData = ByteArray(1) { algorithm }

            bridge.send(it.toByteBuffer())
        }
    }

    protected abstract suspend fun responseRequest(request: EapRequest)

    internal fun cancel() {
        jobAuth?.cancel()
        mailbox.close()
    }
}
