package org.matchat.core.rtc.internal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matchat.core.matrix.MatrixSession
import org.matchat.core.rtc.RtcConfig
import org.matchat.core.rtc.TokenService
import org.matchat.core.rtc.TransportConfig
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Fetches a LiveKit JWT from lk-jwt-service (MSC4195, docs/VOICE.md §3). Flow:
 * get a Matrix OpenID token (proves who we are) + our device id, POST them with
 * the room alias to `<tokenEndpoint>/sfu/get`, and the service — after validating
 * the token against our homeserver — returns the SFU url and a JWT. Plain
 * HttpURLConnection + org.json to avoid pulling in another HTTP client.
 *
 * FFI/interop: the endpoint path and request/response shape follow the current
 * lk-jwt-service; confirm against the deployment in spike 2 and adjust here only.
 */
internal class HttpTokenService @Inject constructor(
    private val matrix: MatrixSession,
    private val config: RtcConfig,
) : TokenService {

    override suspend fun fetchToken(roomId: String): TransportConfig? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext null
        val openId = matrix.openIdToken() ?: return@withContext null
        val device = matrix.deviceId() ?: return@withContext null
        runCatching { request(roomId, openId, device) }
            .onFailure { Log.w(TAG, "token fetch failed: ${it.message}") }
            .getOrNull()
    }

    private fun request(
        roomAlias: String,
        openId: org.matchat.core.model.MatrixOpenIdToken,
        deviceId: String,
    ): TransportConfig? {
        val body = JSONObject()
            .put("room", roomAlias)
            .put("device_id", deviceId)
            .put(
                "openid_token",
                JSONObject()
                    .put("access_token", openId.accessToken)
                    .put("token_type", openId.tokenType)
                    .put("matrix_server_name", openId.matrixServerName)
                    .put("expires_in", openId.expiresInSeconds),
            )
            .toString()

        val url = URL(config.tokenEndpoint.trimEnd('/') + SFU_GET_PATH)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "lk-jwt ${conn.responseCode}")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val sfuUrl = json.optString("url").ifBlank { config.livekitUrl }
            val jwt = json.optString("jwt")
            if (jwt.isBlank()) null else TransportConfig(livekitUrl = sfuUrl, token = jwt)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val SFU_GET_PATH = "/sfu/get"
        const val TIMEOUT_MS = 15_000
        const val TAG = "HttpTokenService"
    }
}
