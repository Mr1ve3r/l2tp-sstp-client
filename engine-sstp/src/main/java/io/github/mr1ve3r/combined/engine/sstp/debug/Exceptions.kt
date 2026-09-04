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
package io.github.mr1ve3r.combined.engine.sstp.debug

/** A data unit did not match what the protocol says it should look like. */
internal class ParsingDataUnitException : Exception("Failed to parse data unit")

/**
 * Throws unless [value] holds.
 *
 * Every use is a check on bytes that arrived from the server, so this stays on
 * in release builds: a malformed frame must end the connection, not be waved
 * through into a parser that then reads past the end of the buffer.
 */
internal fun assertAlways(value: Boolean) {
    if (!value) {
        throw AssertionError()
    }
}
