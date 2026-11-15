package cloud.trotter.feed.contract

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The signature scheme and key ids: ECDSA P-256 with SHA-256, base64 DER, keys as base64 DER SubjectPublicKeyInfo. */
object FeedKeys {
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** A key id is the first 16 hex characters of sha256 over the DER public key. */
    const val KEY_ID_HEX_CHARS = 16

    fun keyId(der: ByteArray): String = sha256Hex(der).substring(0, KEY_ID_HEX_CHARS)

    /** The source id a user pin of this key gets, or null when the key is not a canonical P-256 key. */
    fun customSourceId(publicKeyBase64Der: String): String? =
        decode(publicKeyBase64Der)?.let { FeedSources.custom(it.keyId) }

    /** Lowercase hex sha256. Hand-rolled: `java.util.HexFormat` needs API 34 and the app's minSdk is 30. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for ((n, b) in digest.withIndex()) {
            out[2 * n] = HEX_DIGITS[(b.toInt() shr 4) and 0xf]
            out[2 * n + 1] = HEX_DIGITS[b.toInt() and 0xf]
        }
        return String(out)
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Decodes a base64 DER SubjectPublicKeyInfo. Refuses any key that is not on P-256 and any DER that is not the
     * canonical encoding of its key, so one key has exactly one key id here and in every workflow that hashes the DER.
     */
    internal fun decode(publicKeyBase64Der: String): PinnedKey? = try {
        val der = Base64.getDecoder().decode(publicKeyBase64Der)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        if (key is ECPublicKey && isP256(key.params) && key.encoded.contentEquals(der)) PinnedKey(keyId(der), key) else null
    } catch (_: Exception) {
        null
    }

    // NIST P-256 (secp256r1) domain parameters, hard-coded: Android's providers do not all offer EC AlgorithmParameters.
    private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_A = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
    private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val P256_GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val P256_GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
    private val P256_N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    private fun isP256(params: ECParameterSpec): Boolean {
        val field = params.curve.field as? ECFieldFp ?: return false
        return field.p == P256_P && params.curve.a == P256_A && params.curve.b == P256_B &&
            params.generator.affineX == P256_GX && params.generator.affineY == P256_GY &&
            params.order == P256_N && params.cofactor == 1
    }
}

/** One pinned public key and its id. */
internal class PinnedKey(val keyId: String, val publicKey: PublicKey)
