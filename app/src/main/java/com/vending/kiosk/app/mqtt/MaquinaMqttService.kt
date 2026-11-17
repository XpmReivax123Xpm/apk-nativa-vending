package com.vending.kiosk.app.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vending.kiosk.BuildConfig
import com.vending.kiosk.app.data.backend.MachineAuthGateway
import com.vending.kiosk.app.data.backend.MachineLoginResult
import com.vending.kiosk.app.data.session.AuthSessionManager
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * El canal de avisos con el backend (MQTT), como servicio en primer plano.
 *
 * Es un SEGUNDO transporte a proposito: la unica puerta HTTP del kiosko sigue siendo `BackendHttp`.
 * Este canal NO decide nada: cuando llega un aviso lo deja en [MqttCommandBus] y la pantalla que este
 * adelante hace la comprobacion de siempre contra la API, que es el unico estado autoritativo.
 *
 * La conexion vive en su propio hilo y no toca la UI: el watchdog de la placa reinicia la tablet a los
 * ~200 s sin alimento, asi que un cuelgue del hilo principal seria un reinicio en cascada.
 *
 * Sin broker configurado el servicio se apaga solo: el kiosko sigue con el sondeo de siempre y una
 * maquina sin MQTT se comporta igual que antes.
 */
class MaquinaMqttService : Service() {

    private var hilo: Thread? = null

    @Volatile private var detenido = false

    @Volatile private var cliente: MqttClient? = null

    /** Ultimos comandos atendidos: un QoS 1 reentregado no puede disparar dos veces la comprobacion. */
    private val comandosVistos: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    override fun onCreate() {
        super.onCreate()
        iniciarPrimerPlano()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (hilo == null) {
            detenido = false
            hilo = Thread({ correr() }, "mqtt-maquina").apply {
                isDaemon = true
                start()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        detenido = true

        // El hilo del canal necesita un tope para alcanzar a publicar la salida: onDestroy corre en
        // el hilo principal y no puede quedarse esperando el acuse del broker.
        val enCurso = hilo
        hilo = null
        enCurso?.interrupt()
        runCatching { enCurso?.join(TIEMPO_DESPEDIDA_MS) }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Cerrar el kiosco desde recientes tambien es salir: sin esto el servicio sigue vivo y la maquina
     * seguiria figurando en linea aunque ya nadie la este usando.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        detener()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Bucle del canal: consigue credenciales, conecta y espera. Si la conexion se cae reintenta con
     * espera creciente; si el broker rechaza la credencial la olvida para pedir una nueva.
     */
    private fun correr() {
        var esperaMs = REINTENTO_MIN_MS

        while (!detenido) {
            when (val credenciales = obtenerCredenciales()) {
                is Credenciales.CanalApagado -> {
                    registrar("sin credenciales del broker: el canal queda apagado")
                    detener()
                    return
                }

                is Credenciales.Reintentar -> {
                    dormir(esperaMs)
                    esperaMs = (esperaMs * 2).coerceAtMost(REINTENTO_MAX_MS)
                }

                is Credenciales.Ok -> {
                    val conecto = conectarYEsperar(credenciales.valor)
                    if (detenido) return

                    esperaMs = if (conecto) {
                        REINTENTO_MIN_MS
                    } else {
                        (esperaMs * 2).coerceAtMost(REINTENTO_MAX_MS)
                    }
                    dormir(esperaMs)
                }
            }
        }
    }

    /** @return `true` si llego a conectarse (asi la espera siguiente vuelve al minimo). */
    private fun conectarYEsperar(credenciales: MqttCredentials): Boolean {
        var conectado = false

        val opciones = MqttConnectOptions().apply {
            isAutomaticReconnect = false
            // Sesion persistente: el broker guarda la suscripcion y encola los avisos que lleguen con
            // la maquina desconectada.
            isCleanSession = false
            keepAliveInterval = KEEPALIVE_SEGUNDOS
            connectionTimeout = TIMEOUT_SEGUNDOS
            userName = credenciales.usuario
            password = credenciales.clave.toCharArray()
            setWill(credenciales.temaEstado, estadoSinLinea(), QOS_AVISO, true)
        }

        // El broker de produccion va con TLS y su CA no es publica: sin el ancla propia el saludo
        // termina en «Trust anchor for certification path not found» y no llega ni un aviso.
        if (credenciales.tls) {
            BrokerTls.socketFactory(this@MaquinaMqttService)?.let { opciones.socketFactory = it }
        }

        val caida = CountDownLatch(1)
        val nuevo = try {
            MqttClient(credenciales.uri, credenciales.usuario, MemoryPersistence())
        } catch (excepcion: MqttException) {
            registrar("no se pudo crear el cliente: ${excepcion.message}")
            return false
        }

        nuevo.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // La suscripcion y la presencia NO se hacen aca: Paho invoca este callback en su hilo de
                // callbacks, y suscribir desde adentro puede quedarse esperando el SUBACK de ese mismo
                // hilo. Se hacen del lado del hilo del servicio, despues de connect().
                registrar("conectado a ${credenciales.uri} (reconexion=$reconnect)")
            }

            override fun connectionLost(cause: Throwable?) {
                registrar("conexion caida: ${cause?.message ?: "sin detalle"}")
                caida.countDown()
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                procesarComando(message)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })

        // Con el destino a la vista, una prueba en el equipo se diagnostica por log: si el backend
        // manda 127.0.0.1 en vez del nombre publico, se ve aca.
        registrar("conectando a ${credenciales.uri} como ${credenciales.usuario}")

        try {
            nuevo.connect(opciones)
            conectado = true
            cliente = nuevo

            // Con cleanSession=false el broker conserva la suscripcion; volver a suscribir es
            // idempotente y cubre la sesion que el broker ya habia olvidado.
            nuevo.subscribe(credenciales.temaComando, QOS_AVISO)
            nuevo.publish(credenciales.temaEstado, estadoEnLinea(), QOS_AVISO, true)

            // Se queda en esta conexion hasta que se caiga o la apaguen.
            while (!detenido && !caida.await(1, TimeUnit.SECONDS)) {
                // sigue conectada
            }
        } catch (excepcion: MqttException) {
            val motivo = excepcion.reasonCode.toInt()

            if (motivo == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                motivo == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
            ) {
                // La credencial guardada ya no sirve: se olvida para pedir otra al backend.
                MqttCredentialStore(this).limpiar()
                registrar("el broker rechazo la credencial: se pedira una nueva")
            } else {
                registrar("no se pudo conectar: ${detalleDe(excepcion)}")
            }
        } finally {
            // Apagado limpio: cuando el cliente manda DISCONNECT el broker NO publica el testamento,
            // asi que sin este aviso la maquina quedaria EN_LINEA. Es el mismo mensaje retenido del
            // will, para que el backend no tenga que distinguir un caso del otro.
            if (detenido) {
                runCatching { nuevo.publish(credenciales.temaEstado, estadoSinLinea(), QOS_AVISO, true) }
            }

            runCatching { if (nuevo.isConnected) nuevo.disconnect() }
            runCatching { nuevo.close() }
            cliente = null
        }

        return conectado
    }

    /**
     * Credenciales del canal: las guardadas o, si no hay, las que entrega el backend.
     *
     * `CanalApagado` apaga el servicio; `Reintentar` es un fallo temporal (red, sesion vencida que no
     * se pudo renovar) y se vuelve a probar con espera creciente.
     */
    private fun obtenerCredenciales(): Credenciales {
        MqttCredentialStore(this).cargar()?.let { return Credenciales.Ok(it) }

        val sesion = AuthSessionManager(this)

        return when (val resultado = MqttCredentialsGateway.pedir(sesion)) {
            is MqttCredentialsResult.Ok -> guardar(resultado.credentials)

            MqttCredentialsResult.NoDisponible -> Credenciales.CanalApagado

            is MqttCredentialsResult.Error -> {
                if (!resultado.sesionVencida) return Credenciales.Reintentar

                // La sesion de maquina vence cada 24 h y el aviso tiene que seguir andando: se renueva
                // con el equipo y el PIN que el kiosko ya tiene guardados.
                val renovada = MachineAuthGateway.refreshSessionWithStoredMachineCredentials(sesion)

                if (renovada !is MachineLoginResult.Success) {
                    // Maquina desactivada: sin sesion no hay credenciales que pedir, y reintentar el
                    // login solo gasta throttle. La pantalla de bloqueo la reactiva y vuelve a arrancar
                    // este servicio despues del login.
                    return if ((renovada as? MachineLoginResult.Error)?.inactive == true) {
                        Credenciales.CanalApagado
                    } else {
                        Credenciales.Reintentar
                    }
                }

                when (val segunda = MqttCredentialsGateway.pedir(sesion)) {
                    is MqttCredentialsResult.Ok -> guardar(segunda.credentials)
                    MqttCredentialsResult.NoDisponible -> Credenciales.CanalApagado
                    is MqttCredentialsResult.Error -> Credenciales.Reintentar
                }
            }
        }
    }

    private fun guardar(credenciales: MqttCredentials): Credenciales {
        MqttCredentialStore(this).guardar(credenciales)
        return Credenciales.Ok(credenciales)
    }

    private fun procesarComando(message: MqttMessage?) {
        val texto = message?.let { runCatching { String(it.payload, Charsets.UTF_8) }.getOrNull() } ?: return
        val json = runCatching { JSONObject(texto) }.getOrNull() ?: return

        val tipo = json.optString("Tipo", "").trim().lowercase(Locale.US)
        val codigo = json.optString("CodigoComando", "").trim()

        if (tipo != TIPO_ESTADO) {
            registrar("aviso de tipo desconocido: $tipo")
            return
        }

        if (!esComandoNuevo(codigo)) {
            registrar("aviso repetido, se ignora ($codigo)")
            return
        }

        registrar("aviso de estado recibido ($codigo)")
        MqttCommandBus.avisarCambioDeEstado()
    }

    private fun esComandoNuevo(codigo: String): Boolean {
        if (codigo.isBlank()) return true

        synchronized(comandosVistos) {
            if (!comandosVistos.add(codigo)) return false

            if (comandosVistos.size > MAX_COMANDOS_RECORDADOS) {
                val iterador = comandosVistos.iterator()
                if (iterador.hasNext()) {
                    iterador.next()
                    iterador.remove()
                }
            }
        }

        return true
    }

    private fun estadoEnLinea(): ByteArray = JSONObject()
        .put("Estado", "EN_LINEA")
        .put("VersionApk", BuildConfig.VERSION_NAME)
        .put("Fecha", ahoraIso())
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun estadoSinLinea(): ByteArray = JSONObject()
        .put("Estado", "SIN_LINEA")
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun ahoraIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun dormir(milisegundos: Long) {
        try {
            Thread.sleep(milisegundos)
        } catch (_: InterruptedException) {
            // La apagaron mientras esperaba.
        }
    }

    private fun detener() {
        detenido = true
        runCatching { stopSelf() }
    }

    private fun iniciarPrimerPlano() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            gestor.createNotificationChannel(
                NotificationChannel(CANAL, "Canal de avisos", NotificationManager.IMPORTANCE_MIN)
            )
        }

        val notificacion: Notification = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Canal de avisos")
            .setContentText("Conectado con el backend de la maquina")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID_NOTIFICACION,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(ID_NOTIFICACION, notificacion)
        }
    }

    private fun registrar(mensaje: String) {
        Log.d(TAG, mensaje)
    }

    /** Paho deja el motivo real (TLS, DNS, timeout) en la causa: sin ella el log no dice nada util. */
    private fun detalleDe(excepcion: Throwable): String =
        generateSequence(excepcion) { it.cause }
            .take(5)
            .map { it.message ?: it.javaClass.simpleName }
            .distinct()
            .joinToString(" / ")

    private sealed interface Credenciales {
        data class Ok(val valor: MqttCredentials) : Credenciales

        data object CanalApagado : Credenciales

        data object Reintentar : Credenciales
    }

    companion object {
        private const val TAG = "MaquinaMqtt"
        private const val CANAL = "kiosk_mqtt"
        private const val ID_NOTIFICACION = 4101
        private const val TIPO_ESTADO = "estado"
        private const val QOS_AVISO = 1
        private const val KEEPALIVE_SEGUNDOS = 60
        private const val TIMEOUT_SEGUNDOS = 15
        private const val REINTENTO_MIN_MS = 5_000L
        private const val REINTENTO_MAX_MS = 120_000L
        private const val MAX_COMANDOS_RECORDADOS = 50

        /** Tope para que el hilo del canal alcance a publicar el SIN_LINEA cuando la apagan. */
        private const val TIEMPO_DESPEDIDA_MS = 2_000L

        /** Arranca el canal. Se puede llamar desde cualquier pantalla: si ya corre, no hace nada. */
        fun iniciar(contexto: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    contexto,
                    Intent(contexto, MaquinaMqttService::class.java)
                )
            }
        }

        /**
         * Corta el canal desde afuera: salir del kiosco. El aviso de SIN_LINEA y el DISCONNECT los
         * hace el propio servicio al destruirse, asi que esto solo lo apaga.
         */
        fun detener(contexto: Context) {
            runCatching { contexto.stopService(Intent(contexto, MaquinaMqttService::class.java)) }
        }
    }
}
