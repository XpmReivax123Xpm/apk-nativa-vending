package com.vending.kiosk.app.ui.idle

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.widget.FrameLayout
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

        /*
         * La superficie se crea con el tamano REAL del video, que es la proporcion que hay que respetar.
         * Se toma de aca y no solo de `onPrepared` porque en varios equipos `getVideoWidth()` todavia vale
         * 0 cuando llega ese callback y el tamano aparece un instante despues.
         */
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                fitToScreen(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    fun show() {
        if (isVisible) return
        isVisible = true
        videoIndex = 0
        overlay.visibility = View.VISIBLE

        video.setOnPreparedListener { player: MediaPlayer ->
            fitToScreen(player.videoWidth, player.videoHeight)
            // El tamano del video puede llegar despues de preparar: se reintenta un par de veces.
            video.postDelayed({ fitToScreen(player.videoWidth, player.videoHeight) }, 200L)
            video.postDelayed({ fitToScreen(player.videoWidth, player.videoHeight) }, 700L)
            if (isVisible) {
                video.start()
            }
        }
        video.setOnCompletionListener {
            playNextVideo()
        }
        video.setOnErrorListener { _, _, _ ->
            if (isVisible) {
                onPlaybackError()
            }
            true
        }
        playVideoAt(videoIndex)
    }

    fun hide() {
        isVisible = false
        videoIndex = 0
        video.pause()
        overlay.visibility = View.GONE
    }

    fun stopPlayback() {
        isVisible = false
        video.setOnPreparedListener(null)
        video.setOnCompletionListener(null)
        video.setOnErrorListener(null)
        video.stopPlayback()
    }

    private fun playNextVideo() {
        if (!isVisible) return
        videoIndex = (videoIndex + 1) % IDLE_VIDEO_RES_IDS.size
        playVideoAt(videoIndex)
    }

    private fun playVideoAt(index: Int) {
        if (!isVisible) return
        val videoResId = IDLE_VIDEO_RES_IDS.getOrNull(index) ?: return
        val uri = Uri.parse("android.resource://${overlay.context.packageName}/$videoResId")
        video.setVideoURI(uri)
        if (isVisible) {
            video.start()
        }
    }

    /**
     * Ajusta el video a la pantalla conservando su relacion de aspecto y lo centra en los dos ejes.
     *
     * Sin esto el video queda en su tamano nativo pegado arriba y, cuando la resolucion no coincide con
     * la pantalla, deja el hueco negro entero abajo en vez de repartirlo entre los dos lados.
     */
    private fun fitToScreen(videoWidth: Int, videoHeight: Int) {
        if (!isVisible || videoWidth <= 0 || videoHeight <= 0) return

        val availableWidth = overlay.width
        val availableHeight = overlay.height
        if (availableWidth <= 0 || availableHeight <= 0) return

        val scale = minOf(
            availableWidth.toFloat() / videoWidth,
            availableHeight.toFloat() / videoHeight
        )
        val width = (videoWidth * scale).toInt().coerceIn(1, availableWidth)
        val height = (videoHeight * scale).toInt().coerceIn(1, availableHeight)

        if (video.width == width && video.height == height) return

        Log.d(
            TAG,
            "video de inactividad ${videoWidth}x${videoHeight} -> caja ${width}x${height} " +
                "(pantalla ${availableWidth}x${availableHeight})"
        )

        video.holder.setFixedSize(width, height)
        video.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    private companion object {
        private const val TAG = "IdleVideo"

        private val IDLE_VIDEO_RES_IDS = intArrayOf(
            R.raw.video_de_vengan,
            R.raw.presentacion_pago_facil,
            R.raw.sra_nelly_labor_aqui
        )
    }
}
