package io.github.mr1ve3r.combined.core.profile

import androidx.room.TypeConverter
import io.github.mr1ve3r.combined.engine.Protocol
import io.github.mr1ve3r.combined.engine.TlsVersion
import io.github.mr1ve3r.combined.engine.TrustPolicy

/**
 * Stores the enum columns of [VpnProfile] as their names.
 *
 * A name that no longer exists — a profile written by a newer build, or a
 * renamed constant — decodes to the value a new profile would get rather than
 * throwing. Room has nowhere to report a failure from here, and one unreadable
 * column should not make the profile list unopenable.
 */
object ProfileConverters {
    @TypeConverter
    @JvmStatic
    fun encodeProtocol(value: Protocol): String = value.name

    @TypeConverter
    @JvmStatic
    fun decodeProtocol(value: String): Protocol = decode(value, Protocol.L2TP)

    @TypeConverter
    @JvmStatic
    fun encodePerAppMode(value: PerAppMode): String = value.name

    @TypeConverter
    @JvmStatic
    fun decodePerAppMode(value: String): PerAppMode = decode(value, PerAppMode.OFF)

    @TypeConverter
    @JvmStatic
    fun encodeTrustPolicy(value: TrustPolicy): String = value.name

    @TypeConverter
    @JvmStatic
    fun decodeTrustPolicy(value: String): TrustPolicy = decode(value, TrustPolicy.SYSTEM)

    @TypeConverter
    @JvmStatic
    fun encodeTlsVersion(value: TlsVersion): String = value.name

    @TypeConverter
    @JvmStatic
    fun decodeTlsVersion(value: String): TlsVersion = decode(value, TlsVersion.DEFAULT)

    private inline fun <reified T : Enum<T>> decode(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: fallback
}
