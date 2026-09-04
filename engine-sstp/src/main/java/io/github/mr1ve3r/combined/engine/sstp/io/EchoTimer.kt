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

internal class EchoTimer(private val interval: Long, private val echoFunction: suspend () -> Unit) {
    private var lastTicked = 0L
    private var deadline = 0L

    private var isEchoWaited = false

    private val isOutOfTime: Boolean
        get() = System.currentTimeMillis() - lastTicked > interval

    private val isDead: Boolean
        get() = System.currentTimeMillis() > deadline

    internal suspend fun checkAlive(): Boolean {
        if (isOutOfTime) {
            if (isEchoWaited) {
                if (isDead) {
                    return false
                }
            } else {
                echoFunction.invoke()
                isEchoWaited = true
                deadline = System.currentTimeMillis() + interval
            }
        }

        return true
    }

    internal fun tick() {
        lastTicked = System.currentTimeMillis()
        isEchoWaited = false
    }
}
