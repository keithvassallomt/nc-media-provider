package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import com.keithvassallo.ncmediaprovider.BuildConfig
import java.security.MessageDigest

/**
 * Debug builds sign in with the server details from local.properties (PLAN 1.2) until Login Flow v2
 * arrives in 4.1. The Keystore write only happens when those details change.
 */
object DebugAccount {
    fun seed(context: Context) {
        if (!BuildConfig.DEBUG || BuildConfig.NC_DEBUG_URL.isBlank() || BuildConfig.NC_DEBUG_APP_PASSWORD.isBlank()) return
        val account = NextcloudAccount(
            baseUrl = BuildConfig.NC_DEBUG_URL.trimEnd('/'),
            loginName = BuildConfig.NC_DEBUG_LOGIN,
            userId = BuildConfig.NC_DEBUG_USER_ID.ifBlank { BuildConfig.NC_DEBUG_LOGIN },
            appPassword = BuildConfig.NC_DEBUG_APP_PASSWORD,
        )
        val folder = LibrarySettings.normalizeFolder(BuildConfig.NC_DEBUG_FOLDER)
        val seed = MessageDigest.getInstance("SHA-256")
            .digest(listOf(account.baseUrl, account.loginName, account.userId, account.appPassword, folder).joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val settings = LibrarySettings(context)
        if (settings.debugSeed == seed) return
        CredentialStore(context).save(account)
        settings.folder = folder
        settings.debugSeed = seed
    }
}
