package com.autotap.app
import com.mthtamim.phonelink.R

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: toggles the selected script from the notification
 * shade, without opening the app or hunting for the floating panel.
 */
class AutoTapTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (RunnerService.running) {
            RunnerService.sendCmd(this, RunnerService.CMD_STOP)
        } else {
            val script = Store.selectedScript(this)
            if (script != null && script.steps.isNotEmpty() && TapAccessibilityService.isEnabled()) {
                RunnerService.start(this, script.id)
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val script = Store.selectedScript(this)
        tile.label = "AutoTap"
        tile.subtitle = when {
            RunnerService.running -> "চলছে: ${RunnerService.activeScriptName}"
            script != null -> script.name
            else -> "script নেই"
        }
        tile.state = if (RunnerService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_autotap)
        tile.updateTile()
    }
}
