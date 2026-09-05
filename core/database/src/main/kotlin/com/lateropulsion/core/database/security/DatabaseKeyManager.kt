package com.lateropulsion.core.database.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.lateropulsion.core.common.LpLog
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SQLCipher passphrase management (ARCHITECTURE §14):
 *  - a random 256-bit database passphrase is generated once per install,
 *  - wrapped with an AES-GCM key that lives in the Android Keystore (StrongBox when the phone has it),
 *  - stored as iv‖ciphertext in the app's no-backup directory.
 * The passphrase never leaves process memory unencrypted; the Keystore key never leaves the secure hardware.
 * Clinician PIN/biometric gate the UI (REQ-SEC-003); the key itself is not user-auth bound so the retention
 * worker and crash recovery can run without a person present.
 */
class DatabaseKeyManager(private val context: Context) {
    private val wrappedFile: File get() = File(context.noBackupFilesDir, "lp_db.key")

    fun passphrase(): ByteArray {
        val key = keystoreKey()
        val f = wrappedFile
        if (f.exists()) {
            val blob = f.readBytes()
            val iv = blob.copyOfRange(0, IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return c.doFinal(ct)
        }
        val pass = ByteArray(PASS_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(pass)
        f.writeBytes(c.iv + ct)
        return pass
    }

    val isStrongBoxBacked: Boolean
        get() = try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry ?: return false
            val factory = javax.crypto.SecretKeyFactory.getInstance(entry.secretKey.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(entry.secretKey, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX else info.isInsideSecureHardware
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generate(strongBox = true) ?: generate(strongBox = false) ?: error("Keystore key generation failed")
    }

    private fun generate(strongBox: Boolean): SecretKey? {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return try {
            gen.init(spec)
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            LpLog.i(TAG, "StrongBox unavailable, falling back to TEE keystore")
            null
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "lp_db_wrap_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_LEN = 12
        const val PASS_LEN = 32
        const val TAG = "DbKey"
    }
}

/** Builds the SQLCipher open-helper factory. Kept separate so the native library loads exactly once. */
object EncryptedOpenHelperFactory {
    @Volatile private var loaded = false

    fun create(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        if (!loaded) synchronized(this) { if (!loaded) { System.loadLibrary("sqlcipher"); loaded = true } }
        return net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase)
    }
}
