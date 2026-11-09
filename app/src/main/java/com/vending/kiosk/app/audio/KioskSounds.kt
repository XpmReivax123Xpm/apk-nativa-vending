package com.vending.kiosk.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.vending.kiosk.R

/** Los avisos sonoros del kiosko, cada uno con su recurso en `res/raw`. */
enum class KioskSound(val resId: Int) {
    ProductSelected(R.raw.producto_seleccionado),
    ProductDeselected(R.raw.producto_deseleccionado),
    ProductReadyToCollect(R.raw.recoger_producto),
    ManualRetry(R.raw.reintento_manual),
    PaymentConfirmed(R.raw.pago_confirmado),
    OrderComplete(R.raw.pedido_completo),
    DispenseStuck(R.raw.dispensacion_atorada)
}

/**
 * Reproductor de los avisos sonoros del kiosko.
 *
 * SoundPool y no MediaPlayer: los samples son cortos y varios se disparan seguidos (el retiro de
 * una unidad y el cierre del pedido, por ejemplo), asi que tienen que sonar al instante y poder
 * pisarse. Se precargan al abrir la pantalla para que el primer toque ya tenga sonido.
 */
class KioskSounds(context: Context) {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds: Map<KioskSound, Int>
    private val readySoundIds = mutableSetOf<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) readySoundIds += sampleId
        }
        soundIds = KioskSound.values().associateWith { soundPool.load(context, it.resId, PRIORITY) }
    }

    fun play(sound: KioskSound) {
        val soundId = soundIds[sound] ?: return
        if (soundId !in readySoundIds) return
        soundPool.play(soundId, VOLUME, VOLUME, PRIORITY, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val VOLUME = 1f
        const val PRIORITY = 1
    }
}
