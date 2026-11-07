package io.github.oruviyex18.andmtproto.ui

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.ProxyService
import io.github.oruviyex18.andmtproto.ProxySettings
import io.github.oruviyex18.andmtproto.ProxyStatus
import io.github.oruviyex18.andmtproto.SettingsStore
import io.github.oruviyex18.andmtproto.UpdateCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Saved settings, the draft being edited, and the proxy status for the screens. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _saved = MutableStateFlow(store.load())
    private val _draft = MutableStateFlow(SettingsDraft(_saved.value))
    private val _telegramLinked = MutableStateFlow(isLinked(_saved.value))
    private val _update = MutableStateFlow(UpdateCheck.available(app))
    private val _miuiDone = MutableStateFlow(uiPrefs.getBoolean(KEY_MIUI_DONE, false))

    /** Edits survive rotation because they live here, not in the composable. */
    val draft: StateFlow<SettingsDraft> = _draft.asStateFlow()
    val status: StateFlow<ProxyStatus> = ProxyCore.state

    /**
     * Telegram has used the proxy with the current port and secret. Remembered
     * across restarts; a new port or secret needs a new link, so it starts over.
     */
    val telegramLinked: StateFlow<Boolean> = _telegramLinked.asStateFlow()
    val update: StateFlow<UpdateCheck.Available?> = _update.asStateFlow()

    /** The user confirmed the MIUI background settings, which can't be read back. */
    val miuiDone: StateFlow<Boolean> = _miuiDone.asStateFlow()

    init {
        ProxyCore.refresh()
        viewModelScope.launch {
            status.collect { if (it.running && it.routed > 0) markLinked() }
        }
    }

    fun edit(draft: SettingsDraft) {
        _draft.value = draft
    }

    sealed interface SaveResult {
        data object Unchanged : SaveResult
        data class Saved(val restarted: Boolean) : SaveResult
        /** [field] is the label of the first field that isn't a whole number. */
        data class Invalid(@StringRes val field: Int) : SaveResult
    }

    /** Saves the draft if it changed and restarts a running proxy with it. */
    fun save(): SaveResult {
        val settings = when (val parsed = _draft.value.parse()) {
            is SettingsDraft.Parsed.NotANumber -> return SaveResult.Invalid(parsed.field)
            is SettingsDraft.Parsed.Valid -> parsed.settings
        }
        if (settings == _saved.value) return SaveResult.Unchanged
        persist(settings)
        _draft.value = SettingsDraft(settings)
        val restart = status.value.running
        if (restart) ProxyService.start(getApplication(), restart = true)
        return SaveResult.Saved(restart)
    }

    /** Saves [newPort] (suggested after a bind error), keeping other unsaved edits. */
    fun usePort(newPort: Int) {
        persist(_saved.value.copy(port = newPort))
        _draft.update { it.edit { copy(port = newPort) }.copy(port = newPort.toString()) }
    }

    /** Link for the saved settings, which are what the proxy runs with. */
    fun link(): String? = ProxyCore.link(_saved.value)

    /** Address the proxy listens on, from the saved settings. */
    fun savedAddress(): String = "${_saved.value.host}:${_saved.value.port}"

    /** [link] with the secret hidden but for its last 4 characters, for display. */
    fun maskedLink(): String? {
        val secret = _saved.value.secret
        return link()?.replace(secret, "••••••••" + secret.takeLast(4))
    }

    fun dismissUpdate() {
        _update.value?.let { UpdateCheck.dismiss(getApplication(), it.version) }
        _update.value = null
    }

    fun markMiuiDone() {
        uiPrefs.edit { putBoolean(KEY_MIUI_DONE, true) }
        _miuiDone.value = true
    }

    private fun markLinked() {
        if (_telegramLinked.value) return
        uiPrefs.edit { putString(KEY_LINKED, linkKey(_saved.value)) }
        _telegramLinked.value = true
    }

    private fun isLinked(settings: ProxySettings) = uiPrefs.getString(KEY_LINKED, null) == linkKey(settings)

    private fun linkKey(settings: ProxySettings) =
        "${settings.port}:${settings.secret.hashCode()}:${settings.fakeTlsDomain}"

    private fun persist(settings: ProxySettings) {
        store.save(settings)
        _saved.value = settings
        _telegramLinked.value = isLinked(settings)
    }

    private companion object {
        const val KEY_LINKED = "telegram_linked"
        const val KEY_MIUI_DONE = "miui_done"
    }
}
