package org.matchat.core.matrix.internal

import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/**
 * Serializes the SDK [Session] record to/from JSON bytes so [SessionFileStore]
 * can persist it. The Session field set is version-sensitive; if the SDK adds or
 * renames a field, this is the one place to update (ARCHITECTURE.md — "exactly
 * one file breaks").
 *
 * FFI: verify the Session field names against the AAR. As of sdk 26.09.x the
 * record is (accessToken, refreshToken?, userId, deviceId, homeserverUrl,
 * oidcData?, slidingSyncVersion).
 */
internal object SessionCodec {

    fun encode(session: Session): ByteArray {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oidcData", session.oidcData)
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
            oidcData = json.optStringOrNull("oidcData"),
            slidingSyncVersion = decodeSlidingSync(json.optString("slidingSyncVersion")),
        )
    }

    private fun encodeSlidingSync(version: SlidingSyncVersion): String =
        when (version) {
            is SlidingSyncVersion.Native -> "native"
            else -> "none"
        }

    private fun decodeSlidingSync(raw: String): SlidingSyncVersion =
        when (raw) {
            "native" -> SlidingSyncVersion.Native
            else -> SlidingSyncVersion.None
        }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
