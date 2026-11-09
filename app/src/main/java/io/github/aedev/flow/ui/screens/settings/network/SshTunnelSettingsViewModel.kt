package io.github.aedev.flow.ui.screens.settings.network

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.SshTunnelConfig
import io.github.aedev.flow.data.local.SshTunnelConfigStore
import io.github.aedev.flow.network.SshTunnelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SshTunnelSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _draft = MutableStateFlow<SshTunnelConfig?>(SshTunnelConfigStore.load(context))
        val draft: StateFlow<SshTunnelConfig?> = _draft.asStateFlow()

        fun edit(transform: (SshTunnelConfig) -> SshTunnelConfig) = _draft.update { it?.let(transform) }

        fun save(): Boolean {
            val config = _draft.value ?: return false
            if (!config.valid) return false
            viewModelScope.launch(Dispatchers.IO) { SshTunnelConfigStore.save(context, config) }
            return true
        }

        fun start(): Boolean {
            val config = _draft.value ?: return false
            if (!config.valid) return false
            viewModelScope.launch {
                withContext(Dispatchers.IO) { SshTunnelConfigStore.save(context, config) }
                SshTunnelService.start(context)
            }
            return true
        }

        fun stop() = SshTunnelService.stop(context)
    }
