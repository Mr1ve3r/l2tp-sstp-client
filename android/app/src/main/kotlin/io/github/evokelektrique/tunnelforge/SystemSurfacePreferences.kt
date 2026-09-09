package io.github.evokelektrique.tunnelforge

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.mr1ve3r.combined.core.profile.ProfileStore

/**
 * Which system surfaces the application puts itself on: the notification's
 * "Disconnect" button and the Quick Settings tile.
 *
 * Both are read straight out of the preferences file [ProfileStore] owns rather
 * than through the store itself, because the one caller that must not be slow
 * is [TunnelVpnService.buildNotification]: it runs on the path that has to call
 * `startForeground` within seconds of the service starting, and opening Room
 * there to read a boolean would be a database on the critical path for nothing.
 *
 * They are not Flutter settings for the same reason the profile store is not:
 * an always-on tunnel, a sticky restart, or a tap on the tile brings the
 * service up with no Dart running to ask (SPEC В.13).
 */
internal object SystemSurfacePreferences {

    /** Whether the ongoing notification carries its "Disconnect" button. */
    fun notificationDisconnectActionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ProfileStore.KEY_NOTIFICATION_ACTION, true)

    /** Whether the Quick Settings tile is offered in the tile editor. */
    fun quickSettingsTileEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ProfileStore.KEY_QUICK_TILE, true)

    /**
     * Shows or hides the Quick Settings tile.
     *
     * A tile is hidden by disabling the component it is declared by: there is
     * no other way to take one out of the system's tile editor. A tile the user
     * had already placed disappears when this runs, and turning it back on puts
     * it back in the editor rather than back in the shade — the setting's
     * subtitle says so.
     */
    fun applyQuickSettingsTile(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, VpnTileService::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        ProfileStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
}
