package com.bordy.app.license

import com.bordy.app.BuildConfig
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import android.util.Base64

/**
 * Token format: base64url(payload_json).base64url(raw_signature_r_s_64bytes)
 * The server signs with WebCrypto which outputs raw r||s (64 bytes).
 * Android's Signature expects DER format, so we convert before verifying.
 */
data class TokenPayload(
    val v: Int,
    val kid: String,
    val did: String,
    val st: String,        // "trial" | "paid" | "owner"
    val tre: Long,         // trial end (epoch ms)
    val pu: Long,          // paid_until (epoch ms)
    val iat: Long,
    val exp: Long,
    val nonce: String,
    val minv: Int,
    val msg: String?,
    val tos_ver: Int
)

object LicenseToken {

    /** Decode base64url (no padding) */
    private fun decodeBase64Url(input: String): ByteArray {
        val padded = input.replace('-', '+').replace('_', '/')
        val paddedFull = padded + "=".repeat((4 - padded.length % 4) % 4)
        return Base64.decode(paddedFull, Base64.DEFAULT)
    }

    /**
     * Convert raw ECDSA signature (r||s, 64 bytes from WebCrypto)
     * to DER format required by Java's Signature class.
     */
    private fun rawToDer(raw: ByteArray): ByteArray {
        val r = raw.sliceArray(0 until 32)
        val s = raw.sliceArray(32 until 64)

        fun encodeInt(v: ByteArray): ByteArray {
            val trimmed = v.dropWhile { it == 0.toByte() }.toByteArray()
            val padded = if (trimmed.isEmpty() || trimmed[0].toInt() and 0x80 != 0)
                byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, padded.size.toByte()) + padded
        }

        val dr = encodeInt(r)
        val ds = encodeInt(s)
        return byteArrayOf(0x30, (dr.size + ds.size).toByte()) + dr + ds
    }

    /** Parse JSON payload string to TokenPayload. Returns null on any error. */
    private fun parsePayload(json: String): TokenPayload? = try {
        val obj = org.json.JSONObject(json)
        TokenPayload(
            v = obj.getInt("v"),
            kid = obj.getString("kid"),
            did = obj.getString("did"),
            st = obj.getString("st"),
            tre = obj.getLong("tre"),
            pu = obj.getLong("pu"),
            iat = obj.getLong("iat"),
            exp = obj.getLong("exp"),
            nonce = obj.getString("nonce"),
            minv = obj.optInt("minv", 1),
            msg = obj.optString("msg").takeIf { it.isNotEmpty() },
            tos_ver = obj.optInt("tos_ver", 1)
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Parse and verify a token string.
     * Returns TokenPayload if signature is valid, null otherwise.
     */
    fun verify(token: String): TokenPayload? {
        val parts = token.split(".")
        if (parts.size != 2) return null

        val payloadB64 = parts[0]
        val signatureB64 = parts[1]

        val payloadBytes = try { decodeBase64Url(payloadB64) } catch (_: Exception) { return null }
        val rawSig = try { decodeBase64Url(signatureB64) } catch (_: Exception) { return null }

        if (rawSig.size != 64) return null

        val payload = parsePayload(String(payloadBytes)) ?: return null
        val derSig = rawToDer(rawSig)

        // Try k1, then k2
        val keysToTry = listOf(
            BuildConfig.LICENSE_PUBLIC_KEY_K1,
            BuildConfig.LICENSE_PUBLIC_KEY_K2
        )

        for (keyB64 in keysToTry) {
            if (keyB64.startsWith("REPLACE")) continue
            try {
                val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
                val publicKey = KeyFactory.getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(keyBytes))
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initVerify(publicKey)
                sig.update(payloadBytes)
                if (sig.verify(derSig)) {
                    return payload
                }
            } catch (_: Exception) {
                // Try next key
            }
        }

        return null
    }
}
