package io.github.mr1ve3r.combined.core.trust.store

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONException

/**
 * Stores `List<String>` columns as a JSON array (SPEC 5.1).
 *
 * `org.json` is part of the platform, so this costs no dependency. It is also
 * the reason the converter is only exercised by the instrumentation tests: the
 * JVM stub in `android.jar` throws from every method.
 *
 * Malformed stored text decodes to an empty list rather than throwing. The only
 * column using this holds subject alternative names, which are shown to the
 * user and checked by the hostname verifier against the live certificate; a row
 * that somehow lost them should degrade the display, not make the certificate
 * screen unopenable.
 */
object StringListConverter {
    @TypeConverter
    @JvmStatic
    fun encode(values: List<String>): String = JSONArray().apply { values.forEach(::put) }.toString()

    @TypeConverter
    @JvmStatic
    fun decode(encoded: String): List<String> = try {
        val array = JSONArray(encoded)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }
    } catch (_: JSONException) {
        emptyList()
    }
}
