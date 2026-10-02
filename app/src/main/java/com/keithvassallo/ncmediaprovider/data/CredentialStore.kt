package com.keithvassallo.ncmediaprovider.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.core.net.toUri
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A Nextcloud sign-in. [loginName] may be an email address; [userId] is what WebDAV paths use. */
data class NextcloudAccount(
    val baseUrl: String,
    val loginName: String,
    val userId: String,
    val appPassword: String,
)

class CredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Every cloud-media-provider callback needs the account, and an uncached [load] costs two
     * binder round trips to keystore2 plus an AES-GCM init. Keep the decrypted result in memory.
     */
    @Volatile
    private var cached: NextcloudAccount? = null

    @Synchronized
    @SuppressLint("UseKtx") // The checked synchronous commit makes saving transactional.
    fun save(account: NextcloudAccount) {
        cached = null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(account.appPassword.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_BASE_URL, account.baseUrl)
                .putString(KEY_LOGIN_NAME, account.loginName)
                .putString(KEY_USER_ID, account.userId)
                .putString(KEY_PASSWORD_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_PASSWORD_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to save the Nextcloud account" }
    }

    fun load(): NextcloudAccount? = cached ?: decrypt()?.also { cached = it }

    /**
     * The saved account, read without touching the Keystore, so it is safe on the picker's 100 ms
     * collection-info path. The collection ID is derived from this, so a transient decryption
     * failure must not make the account look signed out: that would change the collection ID and
     * make MediaProvider throw its whole cache away.
     */
    fun account(): SavedAccount? {
        cached?.let { return SavedAccount(it.baseUrl, it.userId) }
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        if (preferences.getString(KEY_PASSWORD_CIPHER, null) == null) return null
        return SavedAccount(baseUrl, userId)
    }

    data class SavedAccount(val baseUrl: String, val userId: String) {
        /** "user@host", shown as the account in the picker's cloud settings. */
        val displayName: String
            get() = "$userId@${baseUrl.toUri().host ?: baseUrl}"
    }

    @Synchronized
    private fun decrypt(): NextcloudAccount? {
        cached?.let { return it }
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val loginName = preferences.getString(KEY_LOGIN_NAME, null) ?: return null
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        val encrypted = preferences.getString(KEY_PASSWORD_CIPHER, null) ?: return null
        val iv = preferences.getString(KEY_PASSWORD_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val password = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            NextcloudAccount(baseUrl, loginName, userId, password)
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        cached = null
        preferences.edit { clear() }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "nextcloud_credentials"
        const val KEY_ALIAS = "nc_media_provider_app_password"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BASE_URL = "base_url"
        const val KEY_LOGIN_NAME = "login_name"
        const val KEY_USER_ID = "user_id"
        const val KEY_PASSWORD_CIPHER = "app_password_cipher"
        const val KEY_PASSWORD_IV = "app_password_iv"
    }
}
