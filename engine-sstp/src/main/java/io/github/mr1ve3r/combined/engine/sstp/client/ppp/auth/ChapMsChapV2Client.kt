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
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapChallenge
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapFailure
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapMessageFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapResponse
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapSuccess

internal class ChapMsChapV2Client(bridge: SstpBridge) : ChapClient(bridge) {
    private val authClient = MsChapV2Client(bridge)

    override suspend fun responseChallenge(challenge: ChapChallenge) {
        ChapResponse().also {
            it.id = challengeID
            it.valueName = authClient.processChallenge(challenge.valueName)

            bridge.send(it.toByteBuffer())
        }
    }

    override suspend fun processResult(result: ChapMessageFrame) {
        when (result) {
            is ChapSuccess -> {
                if (authClient.verifyAuthenticator(result.message)) {
                    authClient.prepareHlak()

                    bridge.mailbox.send(
                        ControlMessage(Where.CHAP, Result.PROCEEDED),
                    )
                } else {
                    bridge.mailbox.send(
                        ControlMessage(Where.MSCHAPV2, Result.ERR_VERIFICATION_FAILED),
                    )
                }
            }

            is ChapFailure -> {
                bridge.mailbox.send(
                    ControlMessage(Where.MSCHAPV2, Result.ERR_AUTHENTICATION_FAILED),
                )
            }
        }
    }
}
