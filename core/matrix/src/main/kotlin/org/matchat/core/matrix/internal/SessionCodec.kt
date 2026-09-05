package org.matchat.core.matrix.internal

import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/**
 * Serializes the SDK [Session] record to/from JSON bytes for [SessionFileStore].
 * If the SDK changes the Session shape on upgrade, this is the one file to update
 * (ARCHITECTURE.md). Field names confirmed against sdk 26.09.x: accessToken,
 * refreshToken?, userId, deviceId, homeserverUrl, oauthData?, slidingSyncVersion.
 * SlidingSyncVersion is a flat uniffi enum, so its Kotlin entries are
 * UPPER_SNAKE_CASE (NATIVE / NONE).
 */
internal object SessionCodec {

    fun encode(session: Session): ByteArray {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oauthData", session.oauthData)
            .put("slidingSyncVersion", encodeSlidingSync(session.slidingSyncVersion))
        return json.toString().toByteArray()
    }

    fun decode(bytes: ByteArray): Session {
        val json = JSONObject(String(bytes))
        return Session(
            accessToken = json.getString("accessToken"),
            refreshToken = json.optStringOrNull("refreshToken"),
            userId = json.getString("userId"),
            deviceId = json.getString("deviceId"),
            homeserverUrl = json.getString("homeserverUrl"),
            oauthData = json.optStringOrNull("oauthData"),
            slidingSyncVersion = decodeSlidingSync(json.optString("slidingSyncVersion")),
        )
    }

    private fun encodeSlidingSync(version: SlidingSyncVersion): String =
        if (version == SlidingSyncVersion.NATIVE) "native" else "none"

    private fun decodeSlidingSync(raw: String): SlidingSyncVersion =
        if (raw == "native") SlidingSyncVersion.NATIVE else SlidingSyncVersion.NONE

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
