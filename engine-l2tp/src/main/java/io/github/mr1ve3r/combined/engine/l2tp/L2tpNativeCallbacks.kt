package io.github.mr1ve3r.combined.engine.l2tp

import androidx.annotation.Keep

/**
 * Where the native layer's upcalls land.
 *
 * The C code looks its Java peers up by name at load time, so the entry points
 * it calls have to stay exactly where they are, in the application module. This
 * object is what those entry points forward to, so that the work behind them —
 * protecting a socket, announcing the tunnel is up, logging — happens against
 * the engine that is actually running rather than against a `VpnService` the
 * engine is not allowed to know about.
 *
 * At most one engine is installed at a time: only one `VpnService` can be
 * active on Android, and the native layer is a process-wide singleton. When
 * nothing is installed — proxy-only mode, or a shutdown already in flight —
 * every call here is a no-op, and [protect] says so by returning `null` so the
 * caller can fall back rather than silently leaving a socket unprotected.
 */
@Keep
object L2tpNativeCallbacks {
    @Volatile
    private var active: L2tpEngine? = null

    internal fun install(engine: L2tpEngine) {
        active = engine
    }

    /** Removes [engine], if it is still the installed one. A later engine wins. */
    internal fun uninstall(engine: L2tpEngine) {
        if (active === engine) {
            active = null
        }
    }

    /**
     * Keeps a native socket outside the tunnel.
     *
     * @return the result of the running engine's
     *   [io.github.mr1ve3r.combined.engine.SocketProtector], or `null` when no
     *   engine is connected and the caller should protect the socket itself.
     */
    @JvmStatic
    fun protect(fd: Int): Boolean? = active?.protectNativeSocket(fd)

    /** Reports that the poll loop is running and packets are moving. */
    @JvmStatic
    fun tunnelReady(detail: String?) {
        active?.onNativeTunnelReady(detail)
    }

    /**
     * Republishes a native log line on the running engine's event stream
     * (SPEC phase 4.1.5).
     *
     * @param priority an `android.util.Log` priority.
     */
    @JvmStatic
    fun nativeLog(priority: Int, tag: String, message: String) {
        active?.onNativeLog(priority, tag, message)
    }
}
