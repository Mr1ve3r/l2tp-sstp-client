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

import io.github.mr1ve3r.combined.engine.PppAuthMethod
import io.github.mr1ve3r.combined.engine.sstp.ControlMessage
import io.github.mr1ve3r.combined.engine.sstp.Result
import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.SstpEngineConfig
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureNak
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_CHAP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_EAP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_PAP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.AuthOption
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.CHAP_ALGORITHM_MSCHAPV2
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.LcpOptionPack
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.MruOption
import kotlin.math.max
import kotlin.math.min

internal class LcpClient(bridge: SstpBridge) : ConfigClient<LcpConfigureFrame>(Where.LCP, bridge) {
    private var isMruRejected = false

    override fun tryCreateServerReject(request: LcpConfigureFrame): LcpConfigureFrame? {
        val reject = LcpOptionPack()

        if (request.options.unknownOptions.isNotEmpty()) {
            reject.unknownOptions = request.options.unknownOptions
        }

        return if (reject.allOptions.isNotEmpty()) {
            LcpConfigureReject().also {
                it.id = request.id
                it.options = reject
                it.options.order = request.options.order
            }
        } else {
            null
        }
    }

    override fun tryCreateServerNak(request: LcpConfigureFrame): LcpConfigureFrame? {
        val nak = LcpOptionPack()

        val serverMru = request.options.mruOption?.unitSize ?: SstpEngineConfig.DEFAULT_MRU
        if (serverMru < bridge.config.mtu) {
            nak.mruOption = MruOption().also { it.unitSize = bridge.config.mtu }
        }

        val serverAuth = request.options.authOption
        var isAuthAcknowledged = false

        when (serverAuth?.protocol) {
            PPP_PROTOCOL_EAP -> {
                if (bridge.config.isEnabled(PppAuthMethod.EAP_MSCHAPV2)) {
                    bridge.state.currentAuth = PppAuthMethod.EAP_MSCHAPV2
                    isAuthAcknowledged = true
                }
            }

            PPP_PROTOCOL_CHAP -> {
                // Upstream throws when the algorithm field is not exactly one
                // byte, which takes down the connection over a malformed
                // option. Leaving it unacknowledged instead sends a NAK naming
                // what this client does support, which is what the peer can act on.
                val offersMsChapV2 = serverAuth.holder.size == 1 && serverAuth.holder[0] == CHAP_ALGORITHM_MSCHAPV2
                if (offersMsChapV2 && bridge.config.isEnabled(PppAuthMethod.MSCHAPV2)) {
                    bridge.state.currentAuth = PppAuthMethod.MSCHAPV2
                    isAuthAcknowledged = true
                }
            }

            PPP_PROTOCOL_PAP -> {
                if (bridge.config.isEnabled(PppAuthMethod.PAP)) {
                    bridge.state.currentAuth = PppAuthMethod.PAP
                    isAuthAcknowledged = true
                }
            }
        }

        if (!isAuthAcknowledged) {
            val authOption = AuthOption()
            when {
                bridge.config.isEnabled(PppAuthMethod.EAP_MSCHAPV2) -> {
                    authOption.protocol = PPP_PROTOCOL_EAP
                }

                bridge.config.isEnabled(PppAuthMethod.MSCHAPV2) -> {
                    authOption.protocol = PPP_PROTOCOL_CHAP
                    authOption.holder = ByteArray(1) { CHAP_ALGORITHM_MSCHAPV2 }
                }

                bridge.config.isEnabled(PppAuthMethod.PAP) -> {
                    authOption.protocol = PPP_PROTOCOL_PAP
                }

                // The engine refuses a profile with no authentication method
                // before a socket is opened, so this is unreachable.
                else -> error("No PPP authentication method is enabled")
            }

            nak.authOption = authOption
        }

        return if (nak.allOptions.isNotEmpty()) {
            LcpConfigureNak().also {
                it.id = request.id
                it.options = nak
                it.options.order = request.options.order
            }
        } else {
            null
        }
    }

    override fun createServerAck(request: LcpConfigureFrame): LcpConfigureFrame {
        return LcpConfigureAck().also {
            it.id = request.id
            it.options = request.options
        }
    }

    override fun createClientRequest(): LcpConfigureFrame {
        val request = LcpConfigureRequest()

        if (!isMruRejected) {
            request.options.mruOption = MruOption().also { it.unitSize = bridge.state.currentMru }
        }

        return request
    }

    override suspend fun tryAcceptClientNak(nak: LcpConfigureFrame) {
        nak.options.mruOption?.also {
            bridge.state.currentMru = max(min(it.unitSize, bridge.config.mru), SstpEngineConfig.MIN_MRU)
        }
    }

    override suspend fun tryAcceptClientReject(reject: LcpConfigureFrame) {
        reject.options.mruOption?.also {
            isMruRejected = true

            if (SstpEngineConfig.DEFAULT_MRU > bridge.config.mru) {
                bridge.mailbox.send(
                    ControlMessage(Where.LCP_MRU, Result.ERR_OPTION_REJECTED),
                )
            }
        }

        reject.options.authOption?.also {
            bridge.mailbox.send(ControlMessage(Where.LCP_AUTH, Result.ERR_OPTION_REJECTED))
        }
    }
}
