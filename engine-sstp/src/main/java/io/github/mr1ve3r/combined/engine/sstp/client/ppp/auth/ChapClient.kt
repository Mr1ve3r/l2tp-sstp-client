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

import io.github.mr1ve3r.combined.engine.sstp.SstpBridge
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapChallenge
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapFrame
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.ChapMessageFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal abstract class ChapClient(protected val bridge: SstpBridge) {
    internal val mailbox = Channel<ChapFrame>(Channel.BUFFERED)
    protected var challengeID: Byte = 0
    private var jobAuth: Job? = null

    internal fun launchJobAuth() {
        jobAuth = bridge.scope.launch {
            while (isActive) {
                when (val received = mailbox.receive()) {
                    is ChapChallenge -> {
                        challengeID = received.id

                        responseChallenge(received)
                    }

                    is ChapMessageFrame -> {
                        if (received.id == challengeID) {
                            processResult(received)
                        }
                    }
                }
            }
        }
    }

    protected abstract suspend fun responseChallenge(challenge: ChapChallenge)

    protected abstract suspend fun processResult(result: ChapMessageFrame)

    internal fun cancel() {
        jobAuth?.cancel()
        mailbox.close()
    }
}
