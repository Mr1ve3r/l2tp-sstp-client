package io.github.mr1ve3r.combined.engine

/**
 * One entry in an engine's log stream, published through [VpnEngine.events].
 *
 * Events from both engines land in a single buffer in the host, which is why
 * [protocol] is carried on every event rather than inferred from the source.
 *
 * Secrets must never reach this type. Passwords, pre-shared keys and proxy
 * credentials are redacted by the engine before an event is emitted, not by the
 * consumer — a log that is only safe once someone remembers to filter it is not
 * safe. See appendix А of the SPEC.
 *
 * @property timestamp wall-clock time in milliseconds since the epoch.
 * @property level severity of the event.
 * @property protocol which engine produced it.
 * @property tag component within the engine, for filtering.
 * @property message human-readable text, already redacted.
 */
data class EngineLogEvent(
    val timestamp: Long,
    val level: LogLevel,
    val protocol: Protocol,
    val tag: String,
    val message: String,
)

/** Severity of an [EngineLogEvent]. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/** VPN protocol implemented by an engine. */
enum class Protocol {
    L2TP,
    SSTP,
}
