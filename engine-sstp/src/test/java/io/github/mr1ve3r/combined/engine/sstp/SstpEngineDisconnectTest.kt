package io.github.mr1ve3r.combined.engine.sstp

import io.github.mr1ve3r.combined.engine.EngineState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where [SstpEngine.disconnect] does its work.
 *
 * Not a detail of style. Tearing an SSTP session down writes to the network
 * twice -- the CALL_DISCONNECT courtesy packet, and the TLS close_notify that
 * `Socket.close()` sends -- and the host tears tunnels down from
 * `Service.onStartCommand`, which is the main thread. StrictMode makes a
 * network write on the main thread fatal, so a disconnect that runs on the
 * caller's thread takes the whole process down with
 * `NetworkOnMainThreadException`. These tests pin the confinement that stops
 * that: the socket work belongs on the engine's own dispatcher, whichever
 * thread called in.
 */
class SstpEngineDisconnectTest {
    @Test
    fun `disconnect moves its socket work onto the engine dispatcher`() {
        val dispatcher = RecordingDispatcher()
        try {
            val engine = SstpEngine(dispatcher = dispatcher)

            runBlocking { engine.disconnect() }

            // Before the fix this was zero: disconnect() closed the transport
            // inline, on whatever thread the host happened to call it from.
            assertTrue(
                "disconnect() never dispatched onto the engine's dispatcher",
                dispatcher.dispatches.get() > 0,
            )
            assertNotEquals(
                "the teardown ran on the caller's thread",
                Thread.currentThread().name,
                dispatcher.lastRunThread,
            )
            assertTrue(dispatcher.lastRunThread?.startsWith(THREAD_NAME) == true)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `disconnect still reaches the disconnected state`() {
        val dispatcher = RecordingDispatcher()
        try {
            val engine = SstpEngine(dispatcher = dispatcher)

            runBlocking { engine.disconnect() }

            assertEquals(EngineState.Disconnected, engine.state.value)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `a second disconnect is ignored rather than closing twice`() {
        val dispatcher = RecordingDispatcher()
        try {
            val engine = SstpEngine(dispatcher = dispatcher)

            runBlocking { engine.disconnect() }
            val afterFirst = dispatcher.dispatches.get()
            runBlocking { engine.disconnect() }

            assertEquals(afterFirst, dispatcher.dispatches.get())
        } finally {
            dispatcher.close()
        }
    }

    /** A single-threaded dispatcher that records what was handed to it. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME)
        }
        private val delegate = executor.asCoroutineDispatcher()

        val dispatches = AtomicInteger(0)

        @Volatile
        var lastRunThread: String? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context) {
                lastRunThread = Thread.currentThread().name
                block.run()
            }
        }

        fun close() {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val THREAD_NAME = "sstp-test-io"
    }
}
