package com.psc.nhkcoop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * `nhk/device_key` platform channel — the Android half of [DeviceKeyService].
 *
 * Generates / stores an EC P-256 key pair in the AndroidKeyStore (hardware
 * backed where available) and signs challenge nonces with it. Both outputs
 * already match the server's contract:
 *  - `PublicKey.getEncoded()` is X.509 SubjectPublicKeyInfo (SPKI) DER.
 *  - `Signature("SHA256withECDSA")` produces an RFC 3279 DER `SEQUENCE(r, s)`.
 *
 * Keys are namespaced per account by prefixing the Flutter-supplied alias (the
 * Firebase uid), so switching accounts on one device never crosses keys.
 */
class DeviceKeyChannel(messenger: BinaryMessenger) {

    private val channel = MethodChannel(messenger, CHANNEL)

    fun register() {
        channel.setMethodCallHandler { call, result -> handle(call, result) }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "generateKeyPair" -> result.success(generateKeyPair(aliasOf(call)))
                "hasKey" -> result.success(hasKey(aliasOf(call)))
                "getPublicKey" -> result.success(getPublicKey(aliasOf(call)))
                "sign" -> {
                    val nonce = call.argument<String>("nonce")
                        ?: throw IllegalArgumentException("nonce is required")
                    result.success(sign(aliasOf(call), nonce))
                }
                "deleteKey" -> {
                    deleteKey(aliasOf(call))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("device_key_error", e.message, call.method)
        }
    }

    private fun aliasOf(call: MethodCall): String {
        val alias = call.argument<String>("alias")
            ?: throw IllegalArgumentException("alias is required")
        return ALIAS_PREFIX + alias
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generateKeyPair(alias: String): String {
        // Drop any existing key first so a re-enroll always starts clean.
        keyStore().takeIf { it.containsAlias(alias) }?.deleteEntry(alias)

        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE,
        )
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        return Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    }

    private fun hasKey(alias: String): Boolean = keyStore().containsAlias(alias)

    private fun getPublicKey(alias: String): String? {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(alias)) return null
        val certificate = keyStore.getCertificate(alias) ?: return null
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    private fun sign(alias: String, nonceBase64: String): String {
        val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("No key for alias")

        val nonce = Base64.decode(nonceBase64, Base64.DEFAULT)
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(entry.privateKey)
            update(nonce)
        }
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun deleteKey(alias: String) {
        keyStore().takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
    }

    companion object {
        const val CHANNEL = "nhk/device_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_PREFIX = "nhk_devkey_"
    }
}
