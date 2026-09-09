package com.vending.kiosk.app.ui.idle

import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.widget.VideoView
import com.vending.kiosk.R

class IdleVideoOverlayView(
    root: View,
    private val onOverlayTouched: () -> Unit,
    private val onPlaybackError: () -> Unit
) {
    private val overlay: View = root.findViewById(R.id.idleVideoOverlay)
    private val video: VideoView = root.findViewById(R.id.idleVideoView)
    private var videoIndex = 0
    var isVisible = false
        private set

    init {
        overlay.visibility = View.GONE
        overlay.setOnClickListener { onOverlayTouched() }
    }

    fun show() {
        if (isVisible) return
        isVisible = true
        videoIndex = 0
        overlay.visibility = View.VISIBLE

        video.setOnPreparedListener { _: MediaPlayer ->
            video.start()
        }
        video.setOnCompletionListener {
            playNextVideo()
        }
        video.setOnErrorListener { _, _, _ ->
            onPlaybackError()
            true
        }
        playVideoAt(videoIndex)
    }

    fun hide() {
        if (!isVisible) return
        isVisible = false
        videoIndex = 0
        video.pause()
        overlay.visibility = View.GONE
    }

    fun stopPlayback() {
        video.stopPlayback()
    }

    private fun playNextVideo() {
        if (!isVisible) return
        videoIndex = (videoIndex + 1) % IDLE_VIDEO_RES_IDS.size
        playVideoAt(videoIndex)
    }

    private fun playVideoAt(index: Int) {
        val videoResId = IDLE_VIDEO_RES_IDS.getOrNull(index) ?: return
        val uri = Uri.parse("android.resource://${overlay.context.packageName}/$videoResId")
        video.setVideoURI(uri)
        video.start()
    }

    private companion object {
        private val IDLE_VIDEO_RES_IDS = intArrayOf(
            R.raw.video_de_vengan,
            R.raw.presentacion_pago_facil,
            R.raw.sra_nelly_labor_aqui
        )
    }
}
