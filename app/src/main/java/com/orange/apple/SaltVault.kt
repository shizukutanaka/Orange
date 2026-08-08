package com.orange.apple

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-install salt storage hardened with the Android Keystore.
 *
 * THREAT ADDRESSED (KeyDroid, arXiv:2507.07927):
 * The spam cache stores salted hashes (ADR 006). But if the salt itself sits
 * in plaintext in SharedPreferences, an attacker who can read the app's
 * private storage (root, forensic image) recovers the salt and the
 * per-install salting no longer raises their cost — they brute-force the
 * low-entropy numbers against the now-known salt.
 *
 * KeyDroid found 56.3% of apps handling sensitive data fail to use the
 * hardware-backed Keystore. Orange uses it: the salt is encrypted with an
 * AES-256-GCM key generated inside the AndroidKeyStore. That key is
 * non-exportable and, on devices with a TEE, never leaves secure hardware.
 * A forensic image of /data yields only the ciphertext salt; without the
 * hardware key it cannot be decrypted off-device.
 *
 * DELIBERATELY NOT StrongBox: setIsStrongBoxBacked() is not requested. StrongBox
 * is a separate secure element and is far slower than the TEE — published
 * benchmarks put 1 MiB symmetric encryption at ~15 s on a Pixel 8 (vs ~0.4 s in
 * the TEE), and ~63 s on a Pixel 3. Our payload is a 16-byte salt, so we would
 * not pay anything close to those figures, but this decrypt sits on the
 * CallScreeningService hot path, which has a hard 5-second deadline — there is
 * no upside worth spending that budget on. The TEE already satisfies the threat
 * model (non-exportable key, useless /data image). If StrongBox is ever added,
 * it must be benchmarked against that deadline first, not assumed cheap.
 *
 * GRACEFUL DEGRADATION: on a JVM unit-test host (no AndroidKeyStore) or a
 * device where Keystore init fails, this falls back to a plaintext salt so the
 * app still functions. The fallback is strictly weaker but never worse than
 * the pre-Keystore design, and unit tests exercise the salting logic without a
 * device. Production devices (API 24+) all have AndroidKeyStore.
 */
internal object SaltVault {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "orange_salt_key"
    private const val KEY_ENC_SALT = "spam_salt_enc"   // base64(iv|ciphertext)
    private const val KEY_PLAIN_SALT = "spam_salt"      // legacy/fallback plaintext
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val SALT_BYTES = 16

    /**
     * Return the per-install salt hex, creating and persisting it on first use.
     * Prefers a Keystore-encrypted salt; falls back to plaintext if Keystore is
     * unavailable (e.g. JVM test host).
     */
    @Synchronized
    fun salt(prefs: SharedPreferences): String {
        decrypt(prefs)?.let { return it }

        // Legacy or fallback plaintext salt present. Read it, and if the
        // Keystore is now available, migrate it into encrypted storage
        // (self-healing: a device that gains Keystore access upgrades itself).
        prefs.getString(KEY_PLAIN_SALT, null)?.let { existing ->
            encryptAndStore(prefs, existing)   // removes plaintext on success
            return existing
        }

        val fresh = randomHex(SALT_BYTES)
        if (!encryptAndStore(prefs, fresh)) {
            prefs.edit { putString(KEY_PLAIN_SALT, fresh) }       // fallback
        }
        return fresh
    }

    private fun randomHex(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun secretKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()
    } catch (_: Throwable) {
        null
    }

    private fun generateKey(): SecretKey? = try {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        gen.generateKey()
    } catch (_: Throwable) {
        null
    }

    private fun encryptAndStore(prefs: SharedPreferences, saltHex: String): Boolean = try {
        val key = secretKey() ?: return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(saltHex.toByteArray(Charsets.UTF_8))
        val packed = iv + ct
        prefs.edit {
            putString(KEY_ENC_SALT, android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP))
            remove(KEY_PLAIN_SALT)   // remove any legacy plaintext salt
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun decrypt(prefs: SharedPreferences): String? = try {
        val packed = prefs.getString(KEY_ENC_SALT, null)?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        } ?: return null
        val key = secretKey() ?: return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ct = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
}
