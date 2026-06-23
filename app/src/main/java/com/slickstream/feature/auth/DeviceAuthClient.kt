package com.slickstream.feature.auth

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Google's OAuth 2.0 Device Authorization endpoints (RFC 8628), used on
 * Android TV where Credential Manager's Google UI is unavailable. Keyless apart from the public
 * "TVs and Limited Input devices" client id/secret (which Google embeds in app source — not a real
 * secret). Returns a Google ID token that FirebaseSync already knows how to exchange.
 */
@Singleton
class DeviceAuthClient @Inject constructor() {

    private val http = OkHttpClient()

    /** Step 1: ask Google for a device_code + user_code. */
    fun requestCode(clientId: String): DeviceCode {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "openid email profile")
            .build()
        val req = Request.Builder().url(DEVICE_CODE_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("device/code ${resp.code}: $raw")
            val j = JSONObject(raw)
            return DeviceCode(
                deviceCode = j.getString("device_code"),
                userCode = j.getString("user_code"),
                // Google returns verification_url; fall back to RFC's verification_uri just in case.
                verificationUrl = j.optString("verification_url")
                    .ifBlank { j.optString("verification_uri", "https://www.google.com/device") },
                expiresInSec = j.optInt("expires_in", 1800),
                intervalSec = j.optInt("interval", 5),
            )
        }
    }

    /**
     * Step 2 (one poll): exchange the device_code. Returns a [PollResult] discriminating the RFC
     * error codes the caller loops on (authorization_pending / slow_down) from terminal states.
     */
    fun poll(clientId: String, clientSecret: String, deviceCode: String): PollResult {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            val j = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (resp.isSuccessful) {
                val idToken = j.optString("id_token").takeIf { it.isNotBlank() }
                    ?: return PollResult.Error("no_id_token")
                return PollResult.Success(idToken)
            }
            return when (j.optString("error")) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "access_denied" -> PollResult.Denied
                "expired_token", "invalid_grant" -> PollResult.Expired
                else -> PollResult.Error(j.optString("error").ifBlank { "http_${resp.code}" })
            }
        }
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSec: Int,
        val intervalSec: Int,
    )

    sealed interface PollResult {
        data class Success(val idToken: String) : PollResult
        data object Pending : PollResult
        data object SlowDown : PollResult
        data object Denied : PollResult
        data object Expired : PollResult
        data class Error(val code: String) : PollResult
    }

    private companion object {
        const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    }
}
