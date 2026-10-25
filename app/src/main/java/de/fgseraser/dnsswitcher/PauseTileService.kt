package de.fgseraser.dnsswitcher

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile "DNS-Automatik": pauses or resumes the automatic switching. */
class PauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        setAutomatic(this, !Store.isEnabled(this))
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = Store.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) tile.subtitle = if (on) "An" else "Pausiert"
        tile.updateTile()
    }

    companion object {
        /** Switches the automation on/off. Paused = the default profile stays active. */
        fun setAutomatic(context: android.content.Context, on: Boolean) {
            Store.setEnabled(context, on)
            if (on) {
                Store.log(context, "Automatik fortgesetzt")
                SwitchService.instance?.reapply()
            } else {
                SwitchService.restoreDefault(context, "Automatik pausiert")
            }
        }
    }
}
