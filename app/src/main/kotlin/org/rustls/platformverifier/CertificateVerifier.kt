/*
 * Derived from the Android component of rustls-platform-verifier
 * (https://github.com/rustls/rustls-platform-verifier, android/.../CertificateVerifier.kt as
 * released in rustls-platform-verifier-android 0.1.1), Copyright (c) 2022 1Password, used
 * under the MIT license. MatChat's changes are recorded in docs/adr/0010.
 */
package org.rustls.platformverifier

import android.content.Context
import android.net.http.X509TrustManagerExtensions
import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// JNI CONTRACT — the Matrix SDK's native library (rustls-platform-verifier 0.6.2, inside
// sdk-android) loads this class by name and calls exactly one method:
//   CertificateVerifier.verifyCertificateChain(Context, String, String, String[], byte[], long,
//       byte[][]) -> VerificationResult
// and then reads VerificationResult's `code` (int) and `message` (String) fields. Keep the
// package, class, method and field names and the parameter types exactly as they are; R8
// keeps them via proguard-rules.pro. The status values mirror the Rust side's definition.
private enum class StatusCode(val value: Int) {
    Ok(0),
    Unavailable(1),
    Expired(2),
    UnknownCert(3),
    InvalidEncoding(5),
    InvalidExtension(6),
}

private class VerificationResult(
    status: StatusCode,
    @Suppress("unused") val message: String? = null,
) {
    @Suppress("unused")
    private val code: Int = status.value
}

/**
 * The certificate verifier behind every TLS connection the Matrix SDK makes.
 *
 * Two deliberate differences from upstream 0.1.1 (docs/adr/0010, issue #41):
 * - Trust is decided by Android's **platform-default** trust manager — the one
 *   HttpsURLConnection and OkHttp use — which honours the app's network security config
 *   (system CAs plus CAs the user installed). Upstream built its own trust manager over
 *   `AndroidCAStore`, which rejected matrix.org's valid chain on Android 7–11 while the
 *   platform default accepted it on the same device.
 * - No revocation check. Android does none for apps, and upstream's check (OCSP-only, no
 *   CRL fetch) fails certificates that carry no OCSP URL. Element X removes it the same way.
 */
@Suppress("unused") // Only the SDK's native code calls in, over JNI.
internal object CertificateVerifier {
    private const val TAG = "MatChatTls"

    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

    private val trustManager: X509TrustManagerExtensions? by lazy { createTrustManager() }

    private fun createTrustManager(): X509TrustManagerExtensions? = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        // A null KeyStore selects the platform default: the app's network security config.
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?.let { X509TrustManagerExtensions(it) }
    }.onFailure { Log.w(TAG, "no platform trust manager: $it") }.getOrNull()

    @JvmStatic
    @Synchronized
    @Suppress("UnusedParameter", "UnusedPrivateMember", "ReturnCount")
    private fun verifyCertificateChain(
        context: Context,
        serverName: String,
        authMethod: String,
        allowedEkus: Array<String>,
        ocspResponse: ByteArray?,
        time: Long,
        certChain: Array<ByteArray>,
    ): VerificationResult {
        val chain = try {
            certChain.map { certFactory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        } catch (e: CertificateException) {
            return VerificationResult(StatusCode.InvalidEncoding, e.toString())
        }
        // rustls always passes the end-entity certificate first.
        val endEntity = chain.firstOrNull() ?: return VerificationResult(StatusCode.InvalidEncoding)

        try {
            endEntity.checkValidity(Date(time))
        } catch (e: CertificateExpiredException) {
            return VerificationResult(StatusCode.Expired, e.toString())
        } catch (e: CertificateNotYetValidException) {
            return VerificationResult(StatusCode.Expired, e.toString())
        }

        if (!hasAllowedUsage(endEntity, allowedEkus)) return VerificationResult(StatusCode.InvalidExtension)

        val manager = trustManager ?: return VerificationResult(StatusCode.Unavailable)
        // Hostname matching is done on the Rust side; serverName here only selects the
        // network-security-config domain rules.
        return try {
            manager.checkServerTrusted(chain.toTypedArray(), authMethod, serverName)
            VerificationResult(StatusCode.Ok)
        } catch (e: CertificateException) {
            Log.w(TAG, "certificate for $serverName not trusted: $e")
            VerificationResult(StatusCode.UnknownCert, e.toString())
        }
    }

    /** True when the certificate may be used for TLS server auth (or names no EKU at all). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun hasAllowedUsage(certificate: X509Certificate, allowedEkus: Array<String>): Boolean {
        val ekus = try {
            certificate.extendedKeyUsage
        } catch (e: CertificateParsingException) {
            Log.w(TAG, "unreadable certificate EKU: $e")
            return false
        } catch (e: NullPointerException) {
            // Malformed EKU data can crash the platform parser (as Chromium notes).
            Log.w(TAG, "malformed certificate EKU: $e")
            return false
        } ?: return true
        return ekus.any { it in allowedEkus }
    }
}
