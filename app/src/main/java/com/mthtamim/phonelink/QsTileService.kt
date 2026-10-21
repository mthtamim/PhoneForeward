package com.mthtamim.phonelink

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap turns this phone's sender mode on or off
 * without opening the app. Android only adds this service's tile when
 * the user places it in their QS panel.
 */
class QsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val ctx = applicationContext
        val nowOn = Config.isSender(ctx)
        Config.setSender(ctx, !nowOn)
        if (!nowOn || Config.isReceiver(ctx)) {
            LinkService.restart(ctx)
        } else {
            LinkService.stop(ctx)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = if (Config.isSender(applicationContext)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (Config.isSender(applicationContext)) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
