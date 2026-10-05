package com.androidcam.prusa

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.io.IOException

/** Result of a Prusa Connect API call. */
sealed class PrusaResult {
    /** 2xx — accepted. [registered] (info only) comes from the response, if present. */
    data class Ok(
        val registered: Boolean? = null,
    ) : PrusaResult()

    /** 401/403/404 — the token is missing, invalid, or expired. */
    data class InvalidToken(
        val detail: String,
    ) : PrusaResult()

    /** Any other non-2xx response (400, 409, 503, ...). */
    data class Rejected(
        val code: Int,
        val detail: String,
    ) : PrusaResult()

    /** The request could not be completed (no route, timeout, ...). */
    data class NetworkError(
        val detail: String,
    ) : PrusaResult()
}

/**
 * Camera description for `PUT /c/info` — the minimal payload Prusa's own
 * ESP32 camera firmware sends (name, firmware, manufacturer, model,
 * resolution, network info).
 */
data class PrusaCameraInfo(
    val name: String,
    val firmware: String,
    val manufacturer: String,
    val model: String,
    val width: Int,
    val height: Int,
    val wifiIpv4: String?,
)

/**
 * Minimal client for the Prusa Connect camera API
 * (https://connect.prusa3d.com/docs/cameras/openapi/):
 *
 * - `PUT /c/info`     — register/update the camera (JSON)
 * - `PUT /c/snapshot` — upload a JPEG snapshot
 *
 * Both calls are authenticated with the `Token` header (20 chars, created in
 * the Prusa Connect web/app when adding a camera) and the `Fingerprint`
 * header (stable per-device identifier, 16-64 chars).
 */
class PrusaConnectClient(
    private val settingsProvider: () -> PrusaConnectSettings,
) {
    private val http =
        HttpClient(
            CIO.create {
                // CIO 2.3 only exposes a single request timeout.
                requestTimeout = 30_000
            },
        ) {
            expectSuccess = false
        }

    /** Register/update the camera. Returns [PrusaResult.Ok] on 200/201/204. */
    suspend fun sendInfo(info: PrusaCameraInfo): PrusaResult {
        val s = settingsProvider()
        val body =
            buildJsonObject {
                putJsonObject("config") {
                    put("name", info.name)
                    put("firmware", info.firmware)
                    put("manufacturer", info.manufacturer)
                    put("model", info.model)
                    putJsonObject("resolution") {
                        put("width", info.width)
                        put("height", info.height)
                    }
                    if (!info.wifiIpv4.isNullOrBlank() && info.wifiIpv4 != "127.0.0.1") {
                        putJsonObject("network_info") {
                            put("wifi_ipv4", info.wifiIpv4)
                        }
                    }
                }
            }.toString()
        return try {
            val response =
                http.put(url(s, "/c/info")) {
                    header("Token", s.token)
                    header("Fingerprint", s.fingerprint)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            when (response.status) {
                HttpStatusCode.OK,
                HttpStatusCode.Created,
                HttpStatusCode.NoContent,
                -> {
                    PrusaResult.Ok(registeredFrom(response))
                }

                else -> {
                    classify(response)
                }
            }
        } catch (e: IOException) {
            Timber.w("Prusa Connect /c/info failed: ${e.message}")
            PrusaResult.NetworkError(e.message ?: "network error")
        }
    }

    /** Upload one JPEG snapshot. Returns [PrusaResult.Ok] on 204. */
    suspend fun uploadSnapshot(jpeg: ByteArray): PrusaResult {
        val s = settingsProvider()
        return try {
            val response =
                http.put(url(s, "/c/snapshot")) {
                    header("Token", s.token)
                    header("Fingerprint", s.fingerprint)
                    contentType(ContentType.Image.JPEG)
                    setBody(jpeg)
                }
            when (response.status) {
                HttpStatusCode.NoContent,
                HttpStatusCode.OK,
                HttpStatusCode.Created,
                -> PrusaResult.Ok()

                else -> classify(response)
            }
        } catch (e: IOException) {
            Timber.w("Prusa Connect /c/snapshot failed: ${e.message}")
            PrusaResult.NetworkError(e.message ?: "network error")
        }
    }

    /** Release the underlying HTTP client. */
    fun close() {
        http.close()
    }

    private fun url(
        s: PrusaConnectSettings,
        path: String,
    ): String = "https://${s.hostname.trimEnd('/')}$path"

    /** Parse `registered` from the /c/info response, if it is a JSON object. */
    private suspend fun registeredFrom(response: HttpResponse): Boolean? {
        return try {
            val text = response.bodyAsText()
            if (text.isBlank()) return null
            Json
                .parseToJsonElement(text)
                .jsonObject["registered"]
                ?.jsonPrimitive
                ?.booleanOrNull
                ?: true
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun classify(response: HttpResponse): PrusaResult {
        val detail =
            runCatching { response.bodyAsText() }
                .getOrNull()
                .orEmpty()
                .take(200)
                .ifBlank { "HTTP ${response.status.value}" }
        return when (response.status) {
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.NotFound,
            -> PrusaResult.InvalidToken(detail)

            else -> PrusaResult.Rejected(response.status.value, detail)
        }
    }
}
