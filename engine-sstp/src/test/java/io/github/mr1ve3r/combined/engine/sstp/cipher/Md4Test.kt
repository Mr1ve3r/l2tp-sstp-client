package io.github.mr1ve3r.combined.engine.sstp.cipher

import io.github.mr1ve3r.combined.engine.sstp.extension.toHexString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MD4 against the RFC 1320 §A.5 test suite.
 *
 * MD4 is not in the JCE, so this fork carries its own implementation, ported
 * from upstream Open SSTP Client. It is the first step of every MSCHAPv2
 * password hash: if it is wrong, authentication fails against every server and
 * nothing in the failure says why. The RFC's own suite is the cheapest evidence
 * that the port did not lose a rotation constant.
 *
 * The suite also covers the padding boundary for free. Inputs of 62 and 80
 * bytes both push the length field out of the first block, which is where a
 * hand-written pad calculation breaks.
 */
class Md4Test {
    @Test
    fun `matches the RFC 1320 test suite`() {
        val suite = listOf(
            "" to "31d6cfe0d16ae931b73c59d7e0c089c0",
            "a" to "bde52cb31de33e46245e05fbdbd6fb24",
            "abc" to "a448017aaf21d8525fc10ae87aa6729d",
            "message digest" to "d9130a8164549fe818874806e1c7014b",
            "abcdefghijklmnopqrstuvwxyz" to "d79e1c308aa5bbcdeea8ed63df412da9",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to
                "043f8582f241db351ce627e153e7f0e4",
            "1234567890".repeat(8) to "e33b4ddc9c38f2199c3e7b164fcc0536",
        )

        for ((input, expected) in suite) {
            val actual = hashMd4(input.toByteArray(Charsets.US_ASCII)).toHexString().lowercase()

            assertEquals("MD4(\"$input\")", expected, actual)
        }
    }
}
