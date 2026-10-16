package com.vending.kiosk.app.interaction

import android.content.Context
import com.vending.kiosk.BuildConfig
import com.vending.kiosk.integration.backend.contracts.VendingBackendGateway
import com.vending.kiosk.integration.backend.models.BitacoraUploadResult
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * La bitacora de una unidad que se sube DESPUES, sin frenar la maquina.
 *
 * Deja el gzip en una carpeta de pendientes del propio equipo y sube lo que haya cuando el kiosko
 * vuelve a dispensar o cuando arranca: la red del local puede caerse justo en el fallo que interesa
 * diagnosticar, y esa bitacora tiene que llegar igual. El reporte de negocio de la unidad NO espera
 * a esta subida —viaja por su lado—, asi que aca todo es best-effort.
 *
 * Las dispensaciones de PRUEBA (sin pedido) no se suben: no hay unidad a la que colgarlas.
 */
object BitacoraDispensacionUploader {

    private const val CARPETA_PENDIENTES = "bitacora_dispensacion_pendientes"

    /**
     * Deja la bitacora de una unidad comprimida y pendiente de subir.
     *
     * @return `false` cuando esa unidad no se puede subir (sin pedido) o el archivo no se pudo
     * escribir: el kiosko sigue igual y el texto queda en la carpeta de la sesion.
     */
    fun enqueue(context: Context, bitacora: File, pedidoId: Int, pedidoDetalle: Int): Boolean {
        if (pedidoId <= 0 || pedidoDetalle <= 0) return false
        if (!bitacora.exists() || bitacora.length() == 0L) return false

        return try {
            val carpeta = carpetaDePendientes(context)
            if (!carpeta.exists()) carpeta.mkdirs()

            val destino = File(carpeta, nombreDePendiente(pedidoId, pedidoDetalle))
            GZIPOutputStream(FileOutputStream(destino)).use { salida ->
                bitacora.inputStream().use { entrada -> entrada.copyTo(salida) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sube lo pendiente y borra lo que ya no tiene sentido reintentar.
     *
     * Se detiene en el primer fallo TEMPORAL: si la red esta caida, insistir con el resto es gastar
     * el tiempo de arranque sin subir nada.
     *
     * @return cuantas bitacoras quedaron subidas
     */
    suspend fun flush(context: Context, gateway: VendingBackendGateway): Int {
        val pendientes = carpetaDePendientes(context)
            .listFiles { archivo -> archivo.isFile && archivo.name.endsWith(EXTENSION) }
            ?: return 0

        var subidas = 0

        for (archivo in pendientes.sortedBy { it.lastModified() }) {
            val unidad = datosDe(archivo.name)

            if (unidad == null) {
                archivo.delete()
                continue
            }

            val contenido = runCatching { archivo.readBytes() }.getOrNull()

            if (contenido == null || contenido.isEmpty()) {
                archivo.delete()
                continue
            }

            val resultado = gateway.uploadDispensacionBitacora(
                orderId = unidad.first,
                orderDetailId = unidad.second,
                contenido = contenido,
                versionApk = BuildConfig.VERSION_NAME,
            )

            when (resultado) {
                is BitacoraUploadResult.Success -> {
                    archivo.delete()
                    subidas++
                }
                // Rechazada: el backend ya dijo que esa bitacora no va; guardarla no la arregla.
                is BitacoraUploadResult.Rechazada -> archivo.delete()
                is BitacoraUploadResult.Error -> return subidas
            }
        }

        return subidas
    }

    /** `p<pedido>_<detalle>_<sello>.txt.gz` -> (pedido, detalle de la unidad). */
    private fun datosDe(nombre: String): Pair<Int, Int>? {
        val partes = nombre.removeSuffix(EXTENSION).split("_")

        if (partes.size < 3 || !partes[0].startsWith("p")) return null

        val pedidoId = partes[0].removePrefix("p").toIntOrNull() ?: return null
        val pedidoDetalle = partes[1].toIntOrNull() ?: return null

        return pedidoId to pedidoDetalle
    }

    private fun nombreDePendiente(pedidoId: Int, pedidoDetalle: Int): String =
        "p${pedidoId}_${pedidoDetalle}_${System.currentTimeMillis()}$EXTENSION"

    private const val EXTENSION = ".txt.gz"

    private fun carpetaDePendientes(context: Context): File {
        val raiz = context.getExternalFilesDir(null) ?: context.filesDir

        return File(raiz, CARPETA_PENDIENTES)
    }
}
