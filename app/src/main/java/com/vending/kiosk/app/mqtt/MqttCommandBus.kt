package com.vending.kiosk.app.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * El aviso «cambio tu estado» que llega por MQTT, dentro del proceso.
 *
 * Es un contador y no un evento suelto: quien se suscribe tarde ve el ULTIMO aviso en vez de perderlo,
 * y el aviso es idempotente —revisar el estado dos veces no cuesta nada— asi que repetirlo es inofensivo.
 */
object MqttCommandBus {

    private val _avisosDeEstado = MutableStateFlow(0L)

    val avisosDeEstado: StateFlow<Long> = _avisosDeEstado

    private val _avisosDePago = MutableStateFlow(0L)

    /**
     * «Hay un pago confirmado de esta maquina». No lleva datos a proposito: la pantalla que esta
     * esperando un cobro hace la misma consulta de estado de siempre, y si no esta esperando nada, el
     * aviso no hace nada.
     */
    val avisosDePago: StateFlow<Long> = _avisosDePago

    fun avisarCambioDeEstado() {
        _avisosDeEstado.update { it + 1 }
    }

    fun avisarPagoConfirmado() {
        _avisosDePago.update { it + 1 }
    }
}
