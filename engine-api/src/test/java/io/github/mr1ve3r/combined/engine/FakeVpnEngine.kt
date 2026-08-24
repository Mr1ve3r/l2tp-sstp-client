package io.github.mr1ve3r.combined.engine

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [VpnEngine] that negotiates nothing and returns canned parameters.
 *
 * Its job is to prove the contract is implementable without a socket, a
 * `VpnService` or a server — the acceptance criterion for SPEC phase 2. If this
 * class ever needs an Android component to compile, the abstraction has leaked.
 *
 * @property result the parameters [connect] reports as agreed with the server.
 * @property failWith when set, [connect] throws this instead of succeeding.
 */
class FakeVpnEngine(
    private val result: TunnelParams,
    private val failWith: EngineError? = null,
) : VpnEngine {
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val mutableEvents = MutableSharedFlow<EngineLogEvent>(replay = REPLAY)

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()
    override val events: SharedFlow<EngineLogEvent> = mutableEvents.asSharedFlow()

    /** The descriptor handed over by [attachTun], or `null` if that never happened. */
    var attachedTun: ParcelFileDescriptor? = null
        private set

    /** How many times [disconnect] has been called. */
    var disconnectCount: Int = 0
        private set

    override suspend fun connect(
        profile: EngineProfile,
        protector: SocketProtector,
    ): TunnelParams {
        mutableState.value = EngineState.Connecting(STAGE_TRANSPORT)
        log("connecting to ${profile.server}")

        // A real engine protects the socket before connecting it. The fake has
        // no socket, so it protects a descriptor to keep the ordering visible.
        if (!protector.protect(TRANSPORT_FD)) {
            val error = EngineError.Internal("socket protection refused")
            mutableState.value = EngineState.Failed(error)
            throw EngineException(error)
        }

        failWith?.let { error ->
            mutableState.value = EngineState.Failed(error)
            throw EngineException(error)
        }

        mutableState.value = EngineState.Connected(result, CONNECTED_AT)
        log("connected")
        return result
    }

    override fun attachTun(fd: ParcelFileDescriptor) {
        attachedTun = fd
        log("tun attached")
    }

    override suspend fun disconnect() {
        disconnectCount++
        attachedTun = null
        mutableState.value = EngineState.Disconnected
        log("disconnected")
    }

    private fun log(message: String) {
        mutableEvents.tryEmit(
            EngineLogEvent(
                timestamp = CONNECTED_AT,
                level = LogLevel.INFO,
                protocol = Protocol.SSTP,
                tag = TAG,
                message = message,
            ),
        )
    }

    private companion object {
        const val REPLAY = 32
        const val TAG = "FakeVpnEngine"
        const val STAGE_TRANSPORT = "transport"
        const val TRANSPORT_FD = 7
        const val CONNECTED_AT = 1_700_000_000_000L
    }
}
