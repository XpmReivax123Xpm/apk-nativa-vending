package com.vending.kiosk.app.data.backend

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One answered HTTP call: the status, the raw body and the server clock.
 *
 * The server clock matters for the QR: the kiosk's own clock may be off, so the remaining time of a
 * payment code is measured against the `Date` header the backend just sent.
 */
internal data class BackendResponse(
    val statusCode: Int,
    val body: String,
    val serverNowMs: Long?
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    val isUnauthorized: Boolean get() = statusCode == HttpURLConnection.HTTP_UNAUTHORIZED

    /**
     * The body as a JSON object, or `null` when the backend sent no readable JSON.
     *
     * An empty body is a real answer —a proxy that returns 200 without content, a connection that
     * died mid-body— and it must never escape as the parser's own exception ("End of input at
     * character 0") to a kiosk screen: each caller answers with its message.
     */
    fun jsonObjectOrNull(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    /** The `data` object of a resource response (`{"data": {...}}`), empty when there is none. */
    fun dataObject(): JSONObject = jsonObjectOrNull()?.optJSONObject("data") ?: JSONObject()

    /** The `data` list of a collection response (`{"data": [...]}`), empty when there is none. */
    fun dataArray(): List<JSONObject> {
        val array = jsonObjectOrNull()?.optJSONArray("data") ?: JSONArray()

        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /**
     * The message to show when the call failed: what the backend said, plus the field errors of a
     * 422 —the operator has to see WHY it was rejected— and the status code, which is how the
     * callers detect an expired session.
     */
    fun errorMessage(fallback: String): String {
        val detail = runCatching {
            val json = jsonObjectOrNull() ?: JSONObject()
            val message = json.optString("message", "").trim().ifBlank { fallback }
            val errors = json.optJSONObject("errors") ?: JSONObject()
            val keys = errors.keys()

            if (!keys.hasNext()) {
                message
            } else {
                val lines = mutableListOf<String>()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = errors.opt(key)
                    val text = when (value) {
                        is JSONArray -> (0 until value.length()).joinToString("; ") { value.optString(it) }
                        else -> value?.toString().orEmpty()
                    }
                    lines += "$key: $text"
                }
                "$message\n${lines.joinToString("\n")}".trim()
            }
        }.getOrDefault(fallback)

        return "$detail (HTTP $statusCode)"
    }
}

/**
 * The only place that opens a connection to the Laravel backend.
 *
 * Every call is JSON over HTTP under `/api/v1`, with the machine's access token in `Authorization`
 * (the token says WHICH machine is asking: no endpoint takes the machine in the URL). Doing it in one
 * place keeps the timeouts, the headers and the error envelope —which are the same for all of
 * them— from drifting apart between gateways.
 */
internal object BackendHttp {

    private const val DEFAULT_TIMEOUT_MS = 12_000

    fun request(
        method: String,
        path: String,
        authorization: String? = null,
        body: JSONObject? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): BackendResponse {
        val endpoint = BackendConfig.apiUrl + path
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (!authorization.isNullOrBlank()) {
                    setRequestProperty("Authorization", authorization)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val statusCode = connection.responseCode
            // El cuerpo de un 2xx se lee SIN red de seguridad: si la conexion se corta a mitad del
            // cuerpo, el fallo tiene que salir como error de red y no como un cuerpo vacio, que es
            // indistinguible despues de "el servidor no mando el recurso".
            val rawBody = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            return BackendResponse(
                statusCode = statusCode,
                body = rawBody,
                serverNowMs = connection.date.takeIf { it > 0L }
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * One answered call whose body is BINARY —the gzipped bitacora—: the bytes go out untouched and
     * the caller declares the content type.
     *
     * Same envelope, same error handling and same server clock as [request]; the only difference is
     * the body, because a bitacora is text of repeated frames and sending it as JSON (base64) would
     * inflate it by a third for nothing.
     */
    fun requestBinary(
        method: String,
        path: String,
        authorization: String? = null,
        body: ByteArray,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): BackendResponse {
        val endpoint = BackendConfig.apiUrl + path
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (!authorization.isNullOrBlank()) {
                    setRequestProperty("Authorization", authorization)
                }
                extraHeaders.forEach { (header, value) -> setRequestProperty(header, value) }
                doOutput = true
                setRequestProperty("Content-Type", contentType)
            }

            connection.outputStream.use { output -> output.write(body) }

            val statusCode = connection.responseCode
            // El cuerpo de un 2xx se lee SIN red de seguridad: si la conexion se corta a mitad del
            // cuerpo, el fallo tiene que salir como error de red y no como un cuerpo vacio, que es
            // indistinguible despues de "el servidor no mando el recurso".
            val rawBody = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            return BackendResponse(
                statusCode = statusCode,
                body = rawBody,
                serverNowMs = connection.date.takeIf { it > 0L }
            )
        } finally {
            connection?.disconnect()
        }
    }
}
