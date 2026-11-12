package com.vending.kiosk.app

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vending.kiosk.R
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bloqueo total mientras la maquina esta desactivada en el backend.
 *
 * No es una pantalla de error: la maquina sigue existiendo y puede reactivarse. Cada 30 minutos se
 * vuelve a pedir el login con el equipo guardado; cuando el backend lo acepta, se restaura la sesion
 * y se vuelve al kiosko. Mientras tanto la pantalla no deja tocar ni salir: es el unico contenido.
 */
class MachineOutOfServiceActivity : AppCompatActivity() {

    private val authSessionManager by lazy { AuthSessionManager(this) }

    private var lockPulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_machine_out_of_service)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveUi()
        sizeLockToScreenThird()
        startLockPulse()
        startPolling()
    }

    override fun onDestroy() {
        lockPulse?.cancel()
        lockPulse = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true

    override fun onBackPressed() = Unit

    /**
     * El candado ocupa un tercio del ancho de la pantalla y es cuadrado, igual que el viewport del
     * SVG: asi la animacion lo escala sin deformar el dibujo.
     */
    private fun sizeLockToScreenThird() {
        val lock = findViewById<ImageView>(R.id.ivOutOfServiceLock)
        val side = resources.displayMetrics.widthPixels / LOCK_WIDTH_DIVISOR
        val params = lock.layoutParams
        params.width = side
        params.height = side
        lock.layoutParams = params
    }

    /**
     * Latido del candado: opacidad y tamano se calculan en cada cuadro desde la MISMA fase
     * normalizada [0, 1] que entrega el animador, nunca desde el valor del cuadro anterior.
     *
     * Un acumulador (`escala += paso`) arrastra el error de redondeo y en una pantalla que queda
     * encendida dias termina con el candado a cualquier tamano. Aca los topes son exactos y se
     * repiten iguales en cada vuelta: fase 0 -> 0.95 de tamano con la opacidad al maximo, fase 1
     * -> 1.05 con la opacidad al minimo (30%, nunca desaparece). La vuelta de regreso es la curva
     * espejada, asi que los dos extremos coinciden siempre.
     */
    private fun startLockPulse() {
        val lock = findViewById<ImageView>(R.id.ivOutOfServiceLock)
        lockPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_LEG_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                val scale = PULSE_SCALE_MIN + (PULSE_SCALE_MAX - PULSE_SCALE_MIN) * phase
                val alpha = PULSE_ALPHA_MAX - (PULSE_ALPHA_MAX - PULSE_ALPHA_MIN) * phase
                lock.scaleX = scale
                lock.scaleY = scale
                lock.alpha = alpha
            }
            start()
        }
    }

    private fun applyImmersiveUi() {
        val flags = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        window.decorView.systemUiVisibility = flags
    }

    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(RETRY_INTERVAL_MS)
                    if (tryRestoreKiosk()) return@repeatOnLifecycle
                }
            }
        }
    }

    private suspend fun tryRestoreKiosk(): Boolean {
        val result = withContext(Dispatchers.IO) {
            MachineAuthGateway.refreshSessionWithStoredMachineCredentials(authSessionManager)
        }
        if (result !is MachineLoginResult.Success) return false
        if (isFinishing || isDestroyed) return false

        authSessionManager.saveMachineLocation(result.machineLocation)
        val equipo = authSessionManager.getEquipo()
        val machineId = equipo?.machineId?.takeIf { it > 0 } ?: result.machineId
        val machineCode = equipo?.machineCode?.ifBlank { result.machineCode } ?: result.machineCode
        if (machineId <= 0 || machineCode.isBlank()) return false

        startActivity(
            Intent(this, KioskCatalogActivity::class.java).apply {
                putExtra(KioskCatalogActivity.EXTRA_MACHINE_ID, machineId)
                putExtra(KioskCatalogActivity.EXTRA_MACHINE_CODE, machineCode)
                putExtra(KioskCatalogActivity.EXTRA_MACHINE_LOCATION, result.machineLocation)
            }
        )
        finish()
        return true
    }

    private companion object {
        /**
         * Cada sondeo es un login completo (bcrypt del PIN + una linea de log de rechazo en el
         * servidor), asi que el ritmo lo fija el volumen: cada 5 s son 17.280 peticiones por
         * maquina y por dia; cada 30 minutos son 48. El costo es la latencia de la reactivacion:
         * una maquina reactivada puede seguir mostrando esta pantalla hasta media hora.
         */
        private const val RETRY_INTERVAL_MS = 30 * 60 * 1_000L

        private const val LOCK_WIDTH_DIVISOR = 3

        // 2,5 s por tramo (5 s la vuelta completa): por debajo de 2 s el latido se lee como parpadeo.
        private const val PULSE_LEG_MS = 2_500L
        private const val PULSE_SCALE_MIN = 0.95f
        private const val PULSE_SCALE_MAX = 1.05f
        private const val PULSE_ALPHA_MAX = 1f
        private const val PULSE_ALPHA_MIN = 0.30f
    }
}
