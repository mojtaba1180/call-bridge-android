package com.example.callbridge.core

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.callbridge.service.BridgeNotificationManager
import com.example.callbridge.utils.VersionUtils

/**
 * Application entry point initializing lightweight dependency container,
 * notification channels, and default Persian locale with English localization toggle support.
 */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        applySavedOrDefaultLocale()
        container = AppContainer(this)
        BridgeNotificationManager(this).ensureChannelCreated()
        Logger.i("App", "CallBridge initialized on ${VersionUtils.androidVersionLabel()}")
    }

    private fun applySavedOrDefaultLocale() {
        val prefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val langTag = prefs.getString(KEY_LANGUAGE_TAG, DEFAULT_LANGUAGE_TAG) ?: DEFAULT_LANGUAGE_TAG
        val desired = LocaleListCompat.forLanguageTags(langTag)
        if (AppCompatDelegate.getApplicationLocales() != desired) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    companion object {
        const val KEY_LANGUAGE_TAG = "app_language_tag"
        const val DEFAULT_LANGUAGE_TAG = "fa"
    }
}
