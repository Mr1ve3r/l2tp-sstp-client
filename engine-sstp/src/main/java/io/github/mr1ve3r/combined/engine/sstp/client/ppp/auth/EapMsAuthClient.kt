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
import io.github.mr1ve3r.combined.engine.sstp.debug.assertAlways
import io.github.mr1ve3r.combined.engine.sstp.extension.toIntAsUShort
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_HEADER_SIZE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_CHALLENGE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_FAILURE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_RESPONSE
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.CHAP_CODE_SUCCESS
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapMessageField
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapValueNameField
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EAP_TYPE_MS_AUTH
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.EapResponse
import java.nio.ByteBuffer

internal class EapMsAuthClient(bridge: SstpBridge) : EapClient(bridge) {
    override val algorithm = EAP_TYPE_MS_AUTH
    private var challengeID: Byte = 0
    private val authClient = MsChapV2Client(bridge)

    override suspend fun responseRequest(request: EapRequest) {
        val wrapped = ByteBuffer.wrap(request.typeData)

        val opCode = wrapped.get()
        val givenInnerID = wrapped.get()
        val fieldLength = wrapped.getShort().toIntAsUShort() - PPP_HEADER_SIZE
        assertAlways(fieldLength == wrapped.remaining())

        when (opCode) {
            CHAP_CODE_CHALLENGE -> {
                isResultAcceptable = false // ensure this for (re)starting authentication
                challengeID = givenInnerID

                val response = ChapValueNameField().let {
                    it.givenLength = fieldLength
                    it.read(wrapped)

                    authClient.processChallenge(it)
                }

                EapResponse().also {
                    it.id = request.id
                    it.type = EAP_TYPE_MS_AUTH

                    val header = ByteBuffer.allocate(PPP_HEADER_SIZE)
                    header.put(CHAP_CODE_RESPONSE)
                    header.put(challengeID)
                    header.putShort((PPP_HEADER_SIZE + response.length).toShort())

                    it.typeData = header.array() + response.toByteBuffer().array()

                    bridge.send(it.toByteBuffer())
                }
            }

            CHAP_CODE_SUCCESS -> {
                assertAlways(givenInnerID == challengeID)

                val message = ChapMessageField().also {
                    it.givenLength = fieldLength
                    it.read(wrapped)
                }

                if (authClient.verifyAuthenticator(message)) {
                    isResultAcceptable = true
                    authClient.prepareHlak()

                    EapResponse().also {
                        it.id = request.id
                        it.type = EAP_TYPE_MS_AUTH
                        it.typeData = ByteArray(1) { CHAP_CODE_SUCCESS }

                        bridge.send(it.toByteBuffer())
                    }
                } else {
                    bridge.mailbox.send(
                        ControlMessage(Where.MSCHAPV2, Result.ERR_VERIFICATION_FAILED),
                    )
                }
            }

            CHAP_CODE_FAILURE -> {
                assertAlways(givenInnerID == challengeID)

                bridge.mailbox.send(
                    ControlMessage(Where.MSCHAPV2, Result.ERR_AUTHENTICATION_FAILED),
                )
            }
        }
    }
}
