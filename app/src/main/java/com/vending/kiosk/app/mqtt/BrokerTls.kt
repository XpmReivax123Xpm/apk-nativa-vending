package com.vending.kiosk.app.mqtt

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS del canal de avisos.
 *
 * El broker presenta un certificado firmado por una CA propia del servidor —no esta en el almacen de
 * Android ni existe en los registros publicos—, asi que con las anclas del sistema el saludo muere con
 * «Trust anchor for certification path not found» y la maquina no recibe ningun aviso.
 *
 * Las anclas viajan en `assets/broker/mqtt_ca.pem` y tienen que ser el MISMO `ca.pem` que vive en el
 * servidor: uno regenerado o de otra instalacion no valida ese certificado. El almacen del sistema se
 * mantiene en paralelo para que, cuando el servidor pase a un certificado publico, la APK siga
 * conectando sin recompilarse.
 */
internal object BrokerTls {

    private const val TAG = "MaquinaMqtt"
    private const val RUTA_CA = "broker/mqtt_ca.pem"

    @Volatile private var fabrica: SSLSocketFactory? = null
    @Volatile private var avisado = false

    /** Fabrica para el broker, o `null` si no hay anclas que cargar (queda el TLS del sistema). */
    fun socketFactory(contexto: Context): SSLSocketFactory? {
        fabrica?.let { return it }

        synchronized(this) {
            fabrica?.let { return it }

            val anclas = leerAnclas(contexto)

            if (anclas.isEmpty()) {
                if (!avisado) {
                    Log.e(TAG, "falta $RUTA_CA (o no tiene certificados): el broker rechazara el TLS")
                    avisado = true
                }

                return null
            }

            return construir(anclas).also { fabrica = it }
        }
    }

    private fun leerAnclas(contexto: Context): List<X509Certificate> = try {
        contexto.assets.open(RUTA_CA).use { entrada ->
            CertificateFactory.getInstance("X.509")
                .generateCertificates(entrada)
                .filterIsInstance<X509Certificate>()
        }
    } catch (excepcion: Exception) {
        Log.e(TAG, "no se pudo leer $RUTA_CA: ${excepcion.message}")

        emptyList()
    }

    private fun construir(anclas: List<X509Certificate>): SSLSocketFactory? = try {
        val almacen = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anclas.forEachIndexed { indice, ancla -> almacen.setCertificateEntry("broker-$indice", ancla) }

        val compuesto = Compuesto(gestor(almacen), gestor(null))
        val contexto = SSLContext.getInstance("TLS")

        contexto.init(null, arrayOf<TrustManager>(compuesto), null)

        Log.d(TAG, "TLS del broker con ${anclas.size} ancla(s) propia(s)")

        contexto.socketFactory
    } catch (excepcion: Exception) {
        Log.e(TAG, "no se pudo preparar el TLS del broker: ${excepcion.message}")

        null
    }

    /** `null` = almacen del sistema. */
    private fun gestor(almacen: KeyStore?): X509TrustManager? =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(almacen as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()

    /** Acepta el certificado si lo valida cualquiera de los dos almacenes. */
    private class Compuesto(
        private val delBroker: X509TrustManager?,
        private val delSistema: X509TrustManager?
    ) : X509TrustManager {

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            (delBroker?.acceptedIssuers.orEmpty().toList() + delSistema?.acceptedIssuers.orEmpty().toList())
                .toTypedArray()

        override fun checkClientTrusted(cadena: Array<X509Certificate>, tipo: String) {
            val gestor = delSistema ?: delBroker ?: throw CertificateException("Sin gestor de confianza")

            gestor.checkClientTrusted(cadena, tipo)
        }

        override fun checkServerTrusted(cadena: Array<X509Certificate>, tipo: String) {
            var ultimo: CertificateException? = null

            for (gestor in listOfNotNull(delBroker, delSistema)) {
                try {
                    gestor.checkServerTrusted(cadena, tipo)

                    return
                } catch (excepcion: CertificateException) {
                    ultimo = excepcion
                }
            }

            throw ultimo ?: CertificateException("El broker no presento un certificado")
        }
    }
}
