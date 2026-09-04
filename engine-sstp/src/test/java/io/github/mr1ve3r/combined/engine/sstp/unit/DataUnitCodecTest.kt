package io.github.mr1ve3r.combined.engine.sstp.unit

import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.LcpConfigureRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_HDLC_HEADER
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.PPP_PROTOCOL_LCP
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapAuthenticateAck
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.auth.PapAuthenticateRequest
import io.github.mr1ve3r.combined.engine.sstp.unit.ppp.option.MruOption
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SSTP_PACKET_TYPE_CONTROL
import io.github.mr1ve3r.combined.engine.sstp.unit.sstp.SstpCallConnectRequest
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trips for the structures that go on the wire.
 *
 * None of these had a test upstream, because nothing there could be built
 * without a `SharedPreferences` and a `VpnService` behind it.
 */
class DataUnitCodecTest {
    @Test
    fun `the call connect request is the 14 bytes the protocol specifies`() {
        val buffer = SstpCallConnectRequest().toByteBuffer()

        assertEquals(14, buffer.remaining())
        assertEquals(SSTP_PACKET_TYPE_CONTROL, buffer.getShort(0))
        assertEquals(14, buffer.getShort(2).toInt())

        SstpCallConnectRequest().read(buffer)
    }

    @Test
    fun `an LCP configure request carries the MRU it was given`() {
        val request =
            LcpConfigureRequest().also {
                it.id = 7
                it.options.mruOption = MruOption().also { option -> option.unitSize = 1400 }
            }

        val decoded = LcpConfigureRequest().also { it.read(request.toByteBuffer()) }

        assertEquals(7, decoded.id.toInt())
        assertEquals(1400, decoded.options.mruOption?.unitSize)
    }

    @Test
    fun `a PPP frame announces itself as SSTP data with an HDLC header`() {
        val buffer = LcpConfigureRequest().toByteBuffer()

        assertEquals(PPP_HDLC_HEADER, buffer.getShort(4))
        assertEquals(PPP_PROTOCOL_LCP, buffer.getShort(6))
    }

    @Test
    fun `PAP sends the credentials it was given`() {
        val request =
            PapAuthenticateRequest().also {
                it.idField = "alice".toByteArray(Charsets.US_ASCII)
                it.passwordField = "s3cret".toByteArray(Charsets.US_ASCII)
            }

        val decoded = PapAuthenticateRequest().also { it.read(request.toByteBuffer()) }

        assertArrayEquals("alice".toByteArray(Charsets.US_ASCII), decoded.idField)
        assertArrayEquals("s3cret".toByteArray(Charsets.US_ASCII), decoded.passwordField)
    }

    @Test
    fun `a PAP acknowledgement with a message consumes all of it`() {
        // Upstream measured the message against its own empty contents, so it
        // read none of it and left the bytes in the buffer for the next packet
        // boundary to trip over. The check is that the reader lands exactly at
        // the end of the frame.
        val message = "Welcome".toByteArray(Charsets.US_ASCII)
        val frame = ByteBuffer.allocate(HEADER_SIZE + 1 + message.size)
        frame.putShort(0x1000) // SSTP data
        frame.putShort(frame.capacity().toShort())
        frame.putShort(PPP_HDLC_HEADER)
        frame.putShort(0xC023.toShort()) // PAP
        frame.put(2) // authenticate-ack
        frame.put(3) // id
        frame.putShort((frame.capacity() - 8).toShort())
        frame.put(message.size.toByte())
        frame.put(message)
        frame.flip()

        PapAuthenticateAck().read(frame)

        assertEquals(0, frame.remaining())
    }

    private companion object {
        /** SSTP header plus the PPP HDLC header, protocol, code, id and length. */
        private const val HEADER_SIZE = 12
    }
}
