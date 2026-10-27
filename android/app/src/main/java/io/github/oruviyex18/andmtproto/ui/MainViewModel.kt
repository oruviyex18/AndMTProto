package io.github.oruviyex18.andmtproto.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.ProxyService
import io.github.oruviyex18.andmtproto.ProxySettings
import io.github.oruviyex18.andmtproto.ProxyStatus
import io.github.oruviyex18.andmtproto.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Saved settings, the draft being edited, and the proxy status for the main screen. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    private val _saved = MutableStateFlow(store.load())
    private val _draft = MutableStateFlow(SettingsDraft(_saved.value))

    /** Edits survive rotation because they live here, not in the composable. */
    val draft: StateFlow<SettingsDraft> = _draft.asStateFlow()
    val status: StateFlow<ProxyStatus> = ProxyCore.state

    init {
        ProxyCore.refresh()
    }

    fun edit(draft: SettingsDraft) {
        _draft.value = draft
    }

    /**
     * Saves the draft and restarts a running proxy with it. Returns the label
     * of a field that isn't a valid number, or null when saved.
     */
    @StringRes
    fun save(): Int? {
        val settings = when (val parsed = _draft.value.parse()) {
            is SettingsDraft.Parsed.NotANumber -> return parsed.field
            is SettingsDraft.Parsed.Valid -> parsed.settings
        }
        persist(settings)
        _draft.value = SettingsDraft(settings)
        if (status.value.running) ProxyService.start(getApplication(), restart = true)
        return null
    }

    /** Saves [newPort] (suggested after a bind error), keeping other unsaved edits. */
    fun usePort(newPort: Int) {
        persist(_saved.value.copy(port = newPort))
        _draft.update { it.edit { copy(port = newPort) }.copy(port = newPort.toString()) }
    }

    /** Link for the saved settings, which are what the proxy runs with. */
    fun link(): String? = ProxyCore.link(_saved.value)

    private fun persist(settings: ProxySettings) {
        store.save(settings)
        _saved.value = settings
    }
}
