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
package io.github.mr1ve3r.combined.engine.sstp.extension

/**
 * Concatenates [words].
 *
 * It exists so the MSCHAPv2 magic constants can be written as the 16-byte lines
 * RFC 2759 prints them in, instead of one unreadable 100-character literal.
 */
internal fun sum(vararg words: String): String = words.joinToString(separator = "")
