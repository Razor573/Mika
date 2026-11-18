package com.wangzan101.mika.capsule

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.BankingMode

/** Quick Settings tile to toggle Banking mode (pause/resume accessibility and phone helper) in 1 tap. */
class BankingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile(qsTile, this)
    }

    override fun onClick() {
        super.onClick()
        val mode = BankingMode.create(this)
        if (BankingMode.isPaused(this)) mode.resume() else mode.pause()
        syncTile(qsTile, this)
        refresh(this)
    }

    companion object {
        internal fun tileState(paused: Boolean): Int =
            if (paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        fun syncTile(tile: Tile?, context: Context) {
            if (tile == null) return
            val paused = BankingMode.isPaused(context)
            tile.state = tileState(paused)
            tile.label = context.getString(R.string.banking_title)
            tile.icon = Icon.createWithResource(context, R.drawable.ic_notification)
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = context.getString(
                    if (paused) R.string.banking_helper_paused else R.string.banking_active,
                )
            }
            tile.updateTile()
        }

        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, BankingTileService::class.java))
            }
        }

        fun requestAddTile(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val sbm = context.getSystemService(StatusBarManager::class.java) ?: return false
            sbm.requestAddTileService(
                ComponentName(context, BankingTileService::class.java),
                context.getString(R.string.banking_title),
                Icon.createWithResource(context, R.drawable.ic_notification),
                context.mainExecutor,
            ) {}
            return true
        }
    }
}
