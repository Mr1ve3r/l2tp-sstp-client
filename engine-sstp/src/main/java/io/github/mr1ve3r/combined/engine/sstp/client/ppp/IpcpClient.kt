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
import io.github.mr1ve3r.combined.engine.sstp.SstpSessionState
import io.github.mr1ve3r.combined.engine.sstp.Where
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureReject
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.IpcpConfigureRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.IpcpAddressOption
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.IpcpOptionPack
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.OPTION_TYPE_IPCP_DNS
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.OPTION_TYPE_IPCP_IP

/**
 * Negotiates the IPv4 address and the DNS server the tunnel will use.
 *
 * The result is read out of [SstpSessionState] by the engine and returned as
 * [TunnelParams][io.github.mr1ve3r.combined.engine.TunnelParams]; upstream it
 * went straight into a `VpnService.Builder` from `IPTerminal`, which is what
 * made routing policy protocol-specific (SPEC 6.4.1, PROVENANCE 3.3).
 *
 * Upstream also had a preference for requesting a fixed address. There is no
 * such field in `EngineProfile.Sstp`, so this client always asks for whatever
 * the server assigns: it sends the all-zero address, which is how PPP spells
 * "you choose".
 */
internal class IpcpClient(bridge: SstpBridge) : ConfigClient<IpcpConfigureFrame>(Where.IPCP, bridge) {
    private var isDnsRejected = false

    override fun tryCreateServerReject(request: IpcpConfigureFrame): IpcpConfigureFrame? {
        val reject = IpcpOptionPack()

        if (request.options.unknownOptions.isNotEmpty()) {
            reject.unknownOptions = request.options.unknownOptions
        }

        request.options.dnsOption?.also { // client doesn't have dns server
            reject.dnsOption = request.options.dnsOption
        }

        return if (reject.allOptions.isNotEmpty()) {
            IpcpConfigureReject().also {
                it.id = request.id
                it.options = reject
                it.options.order = request.options.order
            }
        } else {
            null
        }
    }

    override fun tryCreateServerNak(request: IpcpConfigureFrame): IpcpConfigureFrame? {
        return null
    }

    override fun createServerAck(request: IpcpConfigureFrame): IpcpConfigureFrame {
        return IpcpConfigureAck().also {
            it.id = request.id
            it.options = request.options
        }
    }

    override fun createClientRequest(): IpcpConfigureFrame {
        val request = IpcpConfigureRequest()

        request.options.ipOption = IpcpAddressOption(OPTION_TYPE_IPCP_IP).also {
            bridge.state.currentIPv4.copyInto(it.address)
        }

        if (!isDnsRejected) {
            request.options.dnsOption = IpcpAddressOption(OPTION_TYPE_IPCP_DNS).also {
                bridge.state.currentProposedDns.copyInto(it.address)
            }
        }

        return request
    }

    override suspend fun tryAcceptClientNak(nak: IpcpConfigureFrame) {
        nak.options.ipOption?.also {
            it.address.copyInto(bridge.state.currentIPv4)
        }

        nak.options.dnsOption?.also {
            it.address.copyInto(bridge.state.currentProposedDns)
        }
    }

    override suspend fun tryAcceptClientReject(reject: IpcpConfigureFrame) {
        reject.options.ipOption?.also {
            bridge.mailbox.send(ControlMessage(Where.IPCP_IP, Result.ERR_OPTION_REJECTED))
        }

        reject.options.dnsOption?.also {
            isDnsRejected = true
        }
    }
}
