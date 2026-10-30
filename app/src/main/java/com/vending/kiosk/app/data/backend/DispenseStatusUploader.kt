package com.vending.kiosk.app.data.backend

import android.content.Context
import com.vending.kiosk.integration.backend.contracts.VendingBackendGateway
import com.vending.kiosk.integration.backend.models.DispenseStatusRequest
import com.vending.kiosk.integration.backend.models.DispenseStatusResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * El reporte de estado de una unidad que tiene que llegar SI O SI, aunque la red falle en el momento.
 *
 * Con ese reporte el backend cierra la linea, el pedido y la venta, y descuenta la celda: si se
 * pierde, la venta queda abierta, el inventario no baja y a las 24 h el pedido se cierra por
 * vencimiento. Por eso el reporte se escribe PRIMERO en una carpeta de pendientes del equipo y recien
 * despues se intenta mandar; lo que no salio se reintenta en la proxima unidad, al cargar el catalogo
 * o al arrancar el kiosko.
 *
 * Reenviar es seguro: el backend es idempotente por el estado ACTUAL de la dispensacion, asi que un
 * reintento no descuenta dos veces la celda ni duplica la venta. Lo que el backend rechaza de forma
 * definitiva (la linea no es de ese pedido, la celda no coincide) no se reintenta: se guarda aparte
 * como evidencia.
 *
 * Las dispensaciones de PRUEBA (pedido 0) no se encolan: no hay unidad a la que reportarlas.
 */
object DispenseStatusUploader {

    private const val CARPETA_PENDIENTES = "dispensacion_pendientes"
    private const val CARPETA_RECHAZADOS = "dispensacion_rechazados"
    private const val EXTENSION = ".json"
    private const val SUFIJO_TEMPORAL = ".tmp"
    private const val MAX_RECHAZADOS = 20

    /**
     * Estados terminales de la unidad (4 COMPLETADO, 5 FALLIDO, 7 ATORADO).
     *
     * Un terminal reemplaza a los intermedios de esa misma unidad: reenviar un «en proceso» viejo
     * despues del cierre volveria a abrir un pedido que ya termino.
     */
    private val estadosTerminales = setOf(4, 5, 7)

    /** Serializa los vaciados: dos flush a la vez mandarian el mismo pendiente dos veces. */
    private val candado = Mutex()

    /** Desempata dos reportes del mismo milisegundo sin romper el orden cronologico del nombre. */
    private val secuencia = AtomicLong(0L)

    /**
     * Deja el reporte de una unidad pendiente de enviar.
     *
     * Se llama en el camino de la dispensacion, asi que solo escribe un archivo chico y no habla con
     * la red.
     *
     * @return `false` cuando ese reporte no se puede encolar (sin pedido o sin linea) o el archivo no
     * se pudo escribir; la dispensacion sigue igual.
     */
    fun enqueue(context: Context, request: DispenseStatusRequest): Boolean {
        if (request.orderId <= 0 || request.orderDetailId <= 0) return false
        if (request.dispenseStatusId <= 0) return false

        return try {
            val carpeta = carpetaDePendientes(context)
            if (!carpeta.exists() && !carpeta.mkdirs()) return false

            val pendientes = archivosPendientes(carpeta)

            // Ya hay un reporte igual esperando: repetirlo no agrega nada.
            val mismoReporte = pendientes.any { archivo ->
                val datos = datosDe(archivo) ?: return@any false
                datos.detalle == request.orderDetailId && datos.estado == request.dispenseStatusId
            }
            if (mismoReporte) return true

            if (request.dispenseStatusId in estadosTerminales) {
                pendientes.forEach { archivo ->
                    val datos = datosDe(archivo) ?: return@forEach
                    if (datos.detalle == request.orderDetailId && datos.estado !in estadosTerminales) {
                        archivo.delete()
                    }
                }
            }

            escribirPendiente(carpeta, request)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Manda lo pendiente, en el orden en que ocurrio, y devuelve cuantos reportes quedan sin enviar.
     *
     * Se detiene en el primer fallo TEMPORAL (red caida, token vencido, 5xx): si el backend no
     * responde, insistir con el resto solo gasta tiempo y los deja igual de pendientes.
     */
    suspend fun flush(context: Context, gateway: VendingBackendGateway): Int = candado.withLock {
        val carpeta = carpetaDePendientes(context)
        if (!carpeta.exists()) return@withLock 0

        for (archivo in archivosPendientes(carpeta)) {
            val request = leerPendiente(archivo)

            if (request == null) {
                // Ilegible: no hay forma de mandarlo y no puede bloquear a los demas.
                archivo.delete()
                continue
            }

            val resultado = runCatching { gateway.reportDispenseStatus(request) }
                .getOrElse { DispenseStatusResult.Error(it.message ?: "sin detalle") }

            when (resultado) {
                is DispenseStatusResult.Success -> archivo.delete()
                is DispenseStatusResult.Rechazada -> moverARechazados(carpeta, archivo)
                is DispenseStatusResult.Error -> return@withLock contarPendientes(carpeta)
            }
        }

        contarPendientes(carpeta)
    }

    /** Cuantos reportes esperan envio. No toca la red. */
    fun pendientes(context: Context): Int = contarPendientes(carpetaDePendientes(context))

    /** Los pendientes ordenados como ocurrieron: el nombre arranca con el sello de tiempo. */
    private fun archivosPendientes(carpeta: File): List<File> =
        carpeta.listFiles { archivo -> archivo.isFile && archivo.name.endsWith(EXTENSION) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun contarPendientes(carpeta: File): Int =
        carpeta.listFiles { archivo -> archivo.isFile && archivo.name.endsWith(EXTENSION) }?.size ?: 0

    private fun escribirPendiente(carpeta: File, request: DispenseStatusRequest): Boolean {
        val destino = File(carpeta, nombreDePendiente(request))
        val temporal = File(carpeta, destino.name + SUFIJO_TEMPORAL)

        val cuerpo = JSONObject().apply {
            put("Pedido", request.orderId)
            put("PedidoDetalle", request.orderDetailId)
            put("MaquinaCelda", request.planogramCellId)
            put("DispensacionEstado", request.dispenseStatusId)
            put("CodigoEvento", request.dispenseStatus)
        }

        // Se escribe en un temporal y se renombra: un corte de luz no deja un pendiente a medias.
        temporal.writeText(cuerpo.toString())

        if (!temporal.renameTo(destino)) {
            temporal.delete()
            return false
        }

        return true
    }

    private fun leerPendiente(archivo: File): DispenseStatusRequest? {
        val datos = runCatching { JSONObject(archivo.readText()) }.getOrNull() ?: return null

        val pedidoId = datos.optInt("Pedido", 0)
        val pedidoDetalle = datos.optInt("PedidoDetalle", 0)
        val estado = datos.optInt("DispensacionEstado", 0)

        if (pedidoId <= 0 || pedidoDetalle <= 0 || estado <= 0) return null

        return DispenseStatusRequest(
            orderId = pedidoId,
            orderDetailId = pedidoDetalle,
            planogramCellId = datos.optInt("MaquinaCelda", 0),
            dispenseStatusId = estado,
            dispenseStatus = datos.optString("CodigoEvento", ""),
        )
    }

    /** Saca el rechazado de la cola y guarda los ultimos como evidencia para el operador. */
    private fun moverARechazados(carpetaPendientes: File, archivo: File) {
        val carpeta = File(carpetaPendientes.parentFile, CARPETA_RECHAZADOS)
        if (!carpeta.exists()) carpeta.mkdirs()

        if (!archivo.renameTo(File(carpeta, archivo.name))) {
            archivo.delete()
            return
        }

        carpeta.listFiles()
            ?.sortedBy { it.name }
            ?.dropLast(MAX_RECHAZADOS)
            ?.forEach { it.delete() }
    }

    /** `p<pedido>_<detalle>_e<estado>` dentro del nombre, sin abrir el archivo. */
    private fun datosDe(archivo: File): Datos? {
        val partes = archivo.name.removeSuffix(EXTENSION).split("_")
        if (partes.size != 4) return null

        val pedido = partes[1].removePrefix("p").toIntOrNull() ?: return null
        val detalle = partes[2].removePrefix("l").toIntOrNull() ?: return null
        val estado = partes[3].removePrefix("e").toIntOrNull() ?: return null

        return Datos(pedido, detalle, estado)
    }

    private fun nombreDePendiente(request: DispenseStatusRequest): String {
        val sello = System.currentTimeMillis() * 1000 + secuencia.incrementAndGet() % 1000

        return "%015d_p%d_l%d_e%d%s".format(
            sello,
            request.orderId,
            request.orderDetailId,
            request.dispenseStatusId,
            EXTENSION,
        )
    }

    private fun carpetaDePendientes(context: Context): File {
        val raiz = context.getExternalFilesDir(null) ?: context.filesDir

        return File(raiz, CARPETA_PENDIENTES)
    }

    private data class Datos(val pedido: Int, val detalle: Int, val estado: Int)
}
