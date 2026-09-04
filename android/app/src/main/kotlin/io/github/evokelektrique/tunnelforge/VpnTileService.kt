package io.github.evokelektrique.tunnelforge

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.Keep

/**
 * Quick Settings tile: one tap connects the profile last used, another stops it
 * (SPEC 7.1.4).
 *
 * Stopping happens here, in the tile. Starting does not: the profile's password
 * and pre-shared key live in the Flutter side's encrypted storage, which this
 * process cannot read without the app running, so a tap with nothing connected
 * opens the app instead. Connecting straight from the tile becomes possible in
 * phase 8, when the profiles and their secrets move into Kotlin storage; a
 * second copy of the secrets somewhere Kotlin can reach today would be a bad
 * trade for a shortcut.
 */
@Keep
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        if (TunnelVpnService.isSessionActive()) {
            AppLog.i(TAG, "tile stop requested")
            startService(
                Intent(this, TunnelVpnService::class.java).apply {
                    action = TunnelVpnService.ACTION_STOP
                },
            )
        } else {
            AppLog.i(TAG, "tile open requested")
            openApp()
        }
        refreshTile()
    }

    // The PendingIntent overload only exists from API 34; this build supports
    // API 31, where the Intent one is the only way to open the app from a tile.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_TILE_CONNECT,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = TunnelVpnService.isSessionActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.vpn_tile_label)
        tile.subtitle =
            getString(
                if (active) {
                    R.string.vpn_tile_subtitle_connected
                } else {
                    R.string.vpn_tile_subtitle_disconnected
                },
            )
        tile.updateTile()
    }

    private companion object {
        private const val TAG = "VpnTileService"
        private const val REQUEST_CODE_TILE_CONNECT = 7103
    }
}
