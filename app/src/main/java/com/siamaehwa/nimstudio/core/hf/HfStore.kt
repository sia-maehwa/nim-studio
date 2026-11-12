package com.siamaehwa.nimstudio.core.hf

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.siamaehwa.nimstudio.core.KeystoreCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.hfDataStore by preferencesDataStore(name = "hf_settings")

class HfStore(private val context: Context) {

    companion object {
        private const val TOKEN_ALIAS = "nimstudio_hf_token"
        private const val ENDPOINT_TOKEN_ALIAS = "nimstudio_hf_endpoint_token"

        private val KEY_TOKEN_ENC = stringPreferencesKey("hf_token_enc")
        private val KEY_ENDPOINT = stringPreferencesKey("endpoint_json")
        private val KEY_ENDPOINT_TOKEN_ENC = stringPreferencesKey("endpoint_token_enc")
        private val KEY_SAVED_MODELS = stringPreferencesKey("saved_models")
        private val KEY_LAST_CHAT_MODEL = stringPreferencesKey("last_chat_model")
        private val KEY_LAST_CHAT_PROVIDER = stringPreferencesKey("last_chat_provider")
        private val KEY_LAST_IMAGE_MODEL = stringPreferencesKey("last_image_model")
        private val KEY_LAST_IMAGE_PROVIDER = stringPreferencesKey("last_image_provider")
        private val KEY_USE_ENDPOINT = booleanPreferencesKey("use_endpoint")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val token: Flow<String?> = context.hfDataStore.data.map { prefs ->
        prefs[KEY_TOKEN_ENC]?.let { KeystoreCrypto.decrypt(TOKEN_ALIAS, it) }
    }

    suspend fun saveToken(token: String) {
        val encrypted = KeystoreCrypto.encrypt(TOKEN_ALIAS, token.trim())
        context.hfDataStore.edit { prefs -> prefs[KEY_TOKEN_ENC] = encrypted }
    }

    suspend fun clearToken() {
        context.hfDataStore.edit { prefs -> prefs.remove(KEY_TOKEN_ENC) }
    }

    val endpoint: Flow<HfEndpointConfig> = context.hfDataStore.data.map { prefs ->
        prefs[KEY_ENDPOINT]?.let { stored ->
            runCatching {
                json.decodeFromString(HfEndpointConfig.serializer(), stored)
            }.getOrNull()
        } ?: HfEndpointConfig()
    }

    val endpointToken: Flow<String?> = context.hfDataStore.data.map { prefs ->
        prefs[KEY_ENDPOINT_TOKEN_ENC]?.let {
            KeystoreCrypto.decrypt(ENDPOINT_TOKEN_ALIAS, it)
        }
    }

    suspend fun saveEndpoint(config: HfEndpointConfig, token: String?) {
        context.hfDataStore.edit { prefs ->
            prefs[KEY_ENDPOINT] = json.encodeToString(HfEndpointConfig.serializer(), config)
            val trimmed = token?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_ENDPOINT_TOKEN_ENC)
            } else {
                prefs[KEY_ENDPOINT_TOKEN_ENC] =
                    KeystoreCrypto.encrypt(ENDPOINT_TOKEN_ALIAS, trimmed)
            }
        }
    }

    suspend fun clearEndpoint() {
        context.hfDataStore.edit { prefs ->
            prefs.remove(KEY_ENDPOINT)
            prefs.remove(KEY_ENDPOINT_TOKEN_ENC)
        }
    }

    val savedModels: Flow<List<HfSavedModel>> = context.hfDataStore.data.map { prefs ->
        prefs[KEY_SAVED_MODELS]?.let { stored ->
            runCatching {
                json.decodeFromString(ListSerializer(HfSavedModel.serializer()), stored)
            }.getOrNull()
        }.orEmpty()
    }

    suspend fun saveSavedModels(models: List<HfSavedModel>) {
        val encoded = json.encodeToString(ListSerializer(HfSavedModel.serializer()), models)
        context.hfDataStore.edit { prefs -> prefs[KEY_SAVED_MODELS] = encoded }
    }

    val lastChatModel: Flow<String?> = context.hfDataStore.data.map {
        it[KEY_LAST_CHAT_MODEL]
    }
    val lastChatProvider: Flow<String?> = context.hfDataStore.data.map {
        it[KEY_LAST_CHAT_PROVIDER]
    }
    val lastImageModel: Flow<String?> = context.hfDataStore.data.map {
        it[KEY_LAST_IMAGE_MODEL]
    }
    val lastImageProvider: Flow<String?> = context.hfDataStore.data.map {
        it[KEY_LAST_IMAGE_PROVIDER]
    }

    suspend fun saveLastChat(model: String, provider: String?) {
        context.hfDataStore.edit { prefs ->
            prefs[KEY_LAST_CHAT_MODEL] = model
            if (provider.isNullOrBlank()) {
                prefs.remove(KEY_LAST_CHAT_PROVIDER)
            } else {
                prefs[KEY_LAST_CHAT_PROVIDER] = provider
            }
        }
    }

    val useEndpoint: Flow<Boolean> = context.hfDataStore.data.map {
        it[KEY_USE_ENDPOINT] ?: false
    }

    suspend fun saveUseEndpoint(enabled: Boolean) {
        context.hfDataStore.edit { prefs -> prefs[KEY_USE_ENDPOINT] = enabled }
    }

    suspend fun saveLastImage(model: String, provider: String?) {
        context.hfDataStore.edit { prefs ->
            prefs[KEY_LAST_IMAGE_MODEL] = model
            if (provider.isNullOrBlank()) {
                prefs.remove(KEY_LAST_IMAGE_PROVIDER)
            } else {
                prefs[KEY_LAST_IMAGE_PROVIDER] = provider
            }
        }
    }
}
