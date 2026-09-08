package com.example.touchevidence

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaveEvidenceTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        showIdleTile()
    }

    override fun onClick() {
        super.onClick()
        qsTile?.apply {
            label = "Saving..."
            state = Tile.STATE_ACTIVE
            updateTile()
        }

        scope.launch {
            val result = runCatching { EvidenceLogSaver.save(applicationContext) }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { saved ->
                        qsTile?.apply {
                            label = "Saved"
                            state = Tile.STATE_ACTIVE
                            updateTile()
                        }
                        Toast.makeText(
                            applicationContext,
                            "Saved ${saved.eventCount} DriveTouch events.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        scope.launch {
                            delay(SAVED_PULSE_MS)
                            withContext(Dispatchers.Main) {
                                showIdleTile()
                            }
                        }
                    }
                    .onFailure {
                        qsTile?.apply {
                            label = TILE_LABEL
                            state = Tile.STATE_INACTIVE
                            updateTile()
                        }
                        Toast.makeText(
                            applicationContext,
                            "Could not save DriveTouch evidence.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    override fun onStopListening() {
        showIdleTile()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TILE_LABEL = "Save DriveTouch"
        private const val SAVED_PULSE_MS = 3_000L
    }

    private fun showIdleTile() {
        qsTile?.apply {
            label = TILE_LABEL
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
