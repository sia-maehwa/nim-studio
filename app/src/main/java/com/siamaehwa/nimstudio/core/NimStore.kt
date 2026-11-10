package com.siamaehwa.nimstudio.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.nimDataStore by preferencesDataStore(name = "nim_settings")

class NimStore(private val context: Context) {

    companion object {
        const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
        private val KEY_API_KEY_ENC = stringPreferencesKey("api_key_enc")
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_LAST_MODEL = stringPreferencesKey("last_model")
    }

    val apiKey: Flow<String?> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_API_KEY_ENC]?.let { KeystoreCrypto.decrypt(it) }
    }

    val baseUrl: Flow<String> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    }

    val lastModel: Flow<String?> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_LAST_MODEL]
    }

    suspend fun saveApiKey(key: String) {
        val encrypted = KeystoreCrypto.encrypt(key)
        context.nimDataStore.edit { prefs -> prefs[KEY_API_KEY_ENC] = encrypted }
    }

    suspend fun clearApiKey() {
        context.nimDataStore.edit { prefs -> prefs.remove(KEY_API_KEY_ENC) }
    }

    suspend fun saveBaseUrl(url: String) {
        val value = url.trim().ifEmpty { DEFAULT_BASE_URL }
        context.nimDataStore.edit { prefs -> prefs[KEY_BASE_URL] = value }
    }

    suspend fun saveLastModel(id: String) {
        context.nimDataStore.edit { prefs -> prefs[KEY_LAST_MODEL] = id }
    }
}
