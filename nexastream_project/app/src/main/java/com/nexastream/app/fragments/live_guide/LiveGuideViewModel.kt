package com.nexastream.app.fragments.live_guide

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.live.ProgramReminderScheduler
import com.nexastream.app.live.LiveTvRepository
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.EpgChannelMapping
import com.nexastream.app.models.LiveChannelPreference
import com.nexastream.app.models.LivePlaybackDiagnostic
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.ProgramReminder
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.XmlTvChannel
import com.nexastream.app.providers.IptvOrgProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LiveGuideViewModel : ViewModel() {
    data class UiState(
        val channels: List<TvShow> = emptyList(),
        val programs: List<EpgProgram> = emptyList(),
        val reminders: Set<String> = emptySet(),
        val recordings: List<LiveRecording> = emptyList(),
        val preferences: Map<String, LiveChannelPreference> = emptyMap(),
        val diagnostics: List<LivePlaybackDiagnostic> = emptyList(),
        val epgMappings: Map<String, EpgChannelMapping> = emptyMap(),
        val mappingChannelId: String? = null,
        val mappingCandidates: List<XmlTvChannel> = emptyList(),
        val mappingLoading: Boolean = false,
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var programsJob: Job? = null

    init {
        loadChannels()
        viewModelScope.launch {
            LiveTvRepository.observeReminders().collect { reminders ->
                _state.update { it.copy(reminders = reminders.map(ProgramReminder::programId).toSet()) }
            }
        }
        viewModelScope.launch {
            LiveTvRepository.observeRecordings().collect { recordings ->
                _state.update { it.copy(recordings = recordings) }
            }
        }
        viewModelScope.launch {
            LiveTvRepository.observeChannelPreferences().collect { preferences ->
                _state.update { it.copy(preferences = preferences.associateBy(LiveChannelPreference::channelId)) }
            }
        }
        viewModelScope.launch {
            LiveTvRepository.observeDiagnostics().collect { diagnostics ->
                _state.update { it.copy(diagnostics = diagnostics) }
            }
        }
        viewModelScope.launch {
            LiveTvRepository.observeEpgMappings().collect { mappings ->
                _state.update { it.copy(epgMappings = mappings.associateBy(EpgChannelMapping::channelId)) }
            }
        }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(refreshing = true, error = null) }
            val success = runCatching { IptvOrgProvider.refreshEpg(force = true) }
                .getOrElse {
                    _state.update { state -> state.copy(error = it.message) }
                    false
                }
            _state.update {
                it.copy(
                    refreshing = false,
                    error = if (success) it.error else it.error ?: "Guide refresh failed",
                )
            }
        }
    }

    fun toggleReminder(context: Context, channel: TvShow, program: EpgProgram) {
        viewModelScope.launch(Dispatchers.IO) {
            ProgramReminderScheduler.toggle(context, program, channel.title, channel.id)
        }
    }

    fun toggleFavorite(channel: TvShow) {
        val channelId = channel.liveMetadata?.channelId ?: return
        val favorite = !(_state.value.preferences[channelId]?.isFavorite ?: channel.liveMetadata?.isFavorite ?: false)
        viewModelScope.launch(Dispatchers.IO) {
            LiveTvRepository.setFavorite(channelId, channel.id, channel.title, favorite)
        }
    }

    fun setCustomGroup(channel: TvShow, group: String?) {
        val channelId = channel.liveMetadata?.channelId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            LiveTvRepository.setCustomGroup(channelId, channel.id, channel.title, group)
        }
    }

    fun loadMappingCandidates(channel: TvShow) {
        val channelId = channel.liveMetadata?.channelId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(mappingChannelId = channelId, mappingCandidates = emptyList(), mappingLoading = true)
            }
            val candidates = LiveTvRepository.findEpgMappingCandidates(channelId, channel.title)
            _state.update { it.copy(mappingCandidates = candidates, mappingLoading = false) }
        }
    }

    fun applyMapping(channel: TvShow, candidate: XmlTvChannel) {
        val channelId = channel.liveMetadata?.channelId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(mappingLoading = true) }
            LiveTvRepository.saveEpgMapping(channelId, candidate)
            val refreshed = IptvOrgProvider.refreshEpg(force = true)
            _state.update {
                it.copy(
                    mappingLoading = false,
                    mappingCandidates = emptyList(),
                    mappingChannelId = null,
                    error = if (refreshed) null else "Mapping saved; guide refresh will retry in the background",
                )
            }
        }
    }

    fun removeMapping(channel: TvShow) {
        val channelId = channel.liveMetadata?.channelId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            LiveTvRepository.deleteEpgMapping(channelId)
            IptvOrgProvider.refreshEpg(force = true)
        }
    }

    fun dismissMappingCandidates() {
        _state.update { it.copy(mappingChannelId = null, mappingCandidates = emptyList(), mappingLoading = false) }
    }

    fun clearDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) { LiveTvRepository.clearDiagnostics() }
    }

    private fun loadChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { IptvOrgProvider.getGuideChannels() }
                .onSuccess { channels ->
                    _state.update { it.copy(channels = channels, loading = false, error = null) }
                    observePrograms(channels)
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.message ?: "Unable to load channels") }
                }
        }
    }

    private fun observePrograms(channels: List<TvShow>) {
        programsJob?.cancel()
        val ids = channels.mapNotNull { it.liveMetadata?.channelId }
        val now = System.currentTimeMillis()
        programsJob = viewModelScope.launch {
            LiveTvRepository.observePrograms(
                channelIds = ids,
                startMillis = now - 6 * 60 * 60 * 1000L,
                endMillis = now + 48 * 60 * 60 * 1000L,
            ).collect { programs ->
                _state.update { it.copy(programs = programs) }
            }
        }
    }
}
