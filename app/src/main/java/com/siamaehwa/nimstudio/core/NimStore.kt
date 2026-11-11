package com.siamaehwa.nimstudio.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        private val KEY_AGENT_TREE = stringPreferencesKey("agent_tree_uri")
        private val KEY_AGENT_MODEL = stringPreferencesKey("agent_model")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")
        private val KEY_CHAT_REASONING = stringPreferencesKey("chat_reasoning")
        private val KEY_AGENT_REASONING = stringPreferencesKey("agent_reasoning")
        private val KEY_AGENT_PERMISSION = stringPreferencesKey("agent_permission")
        private val KEY_KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        private val KEY_CHAT_WEB = booleanPreferencesKey("chat_web_search")
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

    val agentTreeUri: Flow<String?> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_AGENT_TREE]
    }

    val agentModel: Flow<String?> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_AGENT_MODEL]
    }

    suspend fun saveAgentTreeUri(uri: String?) {
        context.nimDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(KEY_AGENT_TREE) else prefs[KEY_AGENT_TREE] = uri
        }
    }

    suspend fun saveAgentModel(id: String) {
        context.nimDataStore.edit { prefs -> prefs[KEY_AGENT_MODEL] = id }
    }

    val chatReasoning: Flow<ReasoningEffort> = context.nimDataStore.data.map { prefs ->
        ReasoningEffort.fromId(prefs[KEY_CHAT_REASONING])
    }

    val agentReasoning: Flow<ReasoningEffort> = context.nimDataStore.data.map { prefs ->
        ReasoningEffort.fromId(prefs[KEY_AGENT_REASONING])
    }

    suspend fun saveChatReasoning(effort: ReasoningEffort) {
        context.nimDataStore.edit { prefs ->
            if (effort.id == null) {
                prefs.remove(KEY_CHAT_REASONING)
            } else {
                prefs[KEY_CHAT_REASONING] = effort.id
            }
        }
    }

    suspend fun saveAgentReasoning(effort: ReasoningEffort) {
        context.nimDataStore.edit { prefs ->
            if (effort.id == null) {
                prefs.remove(KEY_AGENT_REASONING)
            } else {
                prefs[KEY_AGENT_REASONING] = effort.id
            }
        }
    }

    val agentPermission: Flow<AgentPermission> = context.nimDataStore.data.map { prefs ->
        AgentPermission.fromId(prefs[KEY_AGENT_PERMISSION])
    }

    suspend fun saveAgentPermission(permission: AgentPermission) {
        context.nimDataStore.edit { prefs -> prefs[KEY_AGENT_PERMISSION] = permission.id }
    }

    val keepAlive: Flow<Boolean> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_KEEP_ALIVE] == true
    }

    suspend fun saveKeepAlive(enabled: Boolean) {
        context.nimDataStore.edit { prefs -> prefs[KEY_KEEP_ALIVE] = enabled }
    }

    val chatWebSearch: Flow<Boolean> = context.nimDataStore.data.map { prefs ->
        prefs[KEY_CHAT_WEB] == true
    }

    suspend fun saveChatWebSearch(enabled: Boolean) {
        context.nimDataStore.edit { prefs -> prefs[KEY_CHAT_WEB] = enabled }
    }

    /** null이면 시스템 설정을 따릅니다. */
    val darkMode: Flow<Boolean?> = context.nimDataStore.data.map { prefs ->
        when (prefs[KEY_DARK_MODE]) {
            "dark" -> true
            "light" -> false
            else -> null
        }
    }

    suspend fun saveDarkMode(enabled: Boolean?) {
        context.nimDataStore.edit { prefs ->
            when (enabled) {
                true -> prefs[KEY_DARK_MODE] = "dark"
                false -> prefs[KEY_DARK_MODE] = "light"
                null -> prefs.remove(KEY_DARK_MODE)
            }
        }
    }
}
