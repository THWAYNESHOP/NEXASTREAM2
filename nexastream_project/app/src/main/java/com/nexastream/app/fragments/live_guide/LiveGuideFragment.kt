package com.nexastream.app.fragments.live_guide

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.nexastream.app.live.LiveTvNavigator
import com.nexastream.app.models.EpgChannelMapping
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.LiveChannelPreference
import com.nexastream.app.models.LivePlaybackDiagnostic
import com.nexastream.app.models.LiveRecording
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.XmlTvChannel
import com.nexastream.app.ui.theme.Nexastream2Theme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class LiveGuideFragment : Fragment() {
    private val viewModel by viewModels<LiveGuideViewModel>()
    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            Nexastream2Theme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LiveGuideScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onPlay = { LiveTvNavigator.playChannel(findNavController(), it) },
                    onPlayRecording = {
                        LiveTvNavigator.playRecording(findNavController(), it.recordingId, it.title)
                    },
                    onReminder = { channel, program ->
                        viewModel.toggleReminder(requireContext(), channel, program)
                    },
                    onFavorite = viewModel::toggleFavorite,
                    onGroup = viewModel::setCustomGroup,
                    onLoadMapping = viewModel::loadMappingCandidates,
                    onApplyMapping = viewModel::applyMapping,
                    onRemoveMapping = viewModel::removeMapping,
                    onDismissMapping = viewModel::dismissMappingCandidates,
                    onClearDiagnostics = viewModel::clearDiagnostics,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LiveGuideScreen(
    state: LiveGuideViewModel.UiState,
    onRefresh: () -> Unit,
    onPlay: (TvShow) -> Unit,
    onPlayRecording: (LiveRecording) -> Unit,
    onReminder: (TvShow, EpgProgram) -> Unit,
    onFavorite: (TvShow) -> Unit,
    onGroup: (TvShow, String?) -> Unit,
    onLoadMapping: (TvShow) -> Unit,
    onApplyMapping: (TvShow, XmlTvChannel) -> Unit,
    onRemoveMapping: (TvShow) -> Unit,
    onDismissMapping: () -> Unit,
    onClearDiagnostics: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val visibleDuration = 4 * 60 * 60 * 1000L
    val minimum = remember { floorToHalfHour(now - 2 * 60 * 60 * 1000L) }
    val maximum = remember { floorToHalfHour(now + 36 * 60 * 60 * 1000L) }
    var anchorMillis by remember { mutableLongStateOf(floorToHalfHour(now)) }
    var filter by remember { mutableStateOf("all") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showMappings by remember { mutableStateOf(false) }
    var groupChannel by remember { mutableStateOf<TvShow?>(null) }
    var groupText by remember { mutableStateOf("") }
    val channelWidth = 220.dp
    val slotWidth = 140.dp
    val slots = 8
    val totalWidth = channelWidth + slotWidth * slots
    val horizontal = rememberScrollState()
    val controlsScroll = rememberScrollState()
    val groups = state.preferences.values.mapNotNull { it.customGroup }.distinct().sorted()
    val visibleChannels = remember(state.channels, state.preferences, filter) {
        state.channels.filter { channel ->
            val channelId = channel.liveMetadata?.channelId
            val preference = channelId?.let(state.preferences::get)
            when {
                filter == "favorites" -> preference?.isFavorite == true
                filter.startsWith("group:") -> preference?.customGroup == filter.removePrefix("group:")
                else -> true
            }
        }
    }
    val programsByChannel = remember(state.programs) { state.programs.groupBy(EpgProgram::channelId) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(controlsScroll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Live guide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = { anchorMillis = (anchorMillis - 2 * 60 * 60 * 1000L).coerceAtLeast(minimum) }) {
                    Icon(Icons.Default.ArrowBack, null)
                    Text("Earlier")
                }
                Button(onClick = { anchorMillis = floorToHalfHour(System.currentTimeMillis()) }) {
                    Icon(Icons.Default.LiveTv, null)
                    Text("Now")
                }
                Button(onClick = { anchorMillis = (anchorMillis + 2 * 60 * 60 * 1000L).coerceAtMost(maximum) }) {
                    Text("Later")
                    Icon(Icons.Default.ArrowForward, null)
                }
                Button(onClick = { showDiagnostics = true }) { Text("Diagnostics") }
                Button(onClick = { showMappings = true }) { Text("EPG mapping") }
                IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Default.Refresh, "Refresh guide")
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("All") })
                FilterChip(
                    selected = filter == "favorites",
                    onClick = { filter = "favorites" },
                    label = { Text("Favorites") },
                    leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) },
                )
                groups.forEach { group ->
                    FilterChip(
                        selected = filter == "group:$group",
                        onClick = { filter = "group:$group" },
                        label = { Text(group) },
                    )
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (state.recordings.isNotEmpty()) RecordingStrip(state.recordings, onPlayRecording)
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Box(
                    Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal),
                ) {
                    LazyColumn(Modifier.width(totalWidth).fillMaxHeight()) {
                        stickyHeader { GuideTimeHeader(anchorMillis, channelWidth, slotWidth, slots) }
                        items(visibleChannels, key = { it.id }) { channel ->
                            val channelId = channel.liveMetadata?.channelId
                            val programs = programsByChannel[channelId].orEmpty().filter {
                                it.endMillis > anchorMillis && it.startMillis < anchorMillis + visibleDuration
                            }
                            GuideChannelRow(
                                channel = channel,
                                preference = channelId?.let(state.preferences::get),
                                programs = programs,
                                reminderIds = state.reminders,
                                anchorMillis = anchorMillis,
                                durationMillis = visibleDuration,
                                channelWidth = channelWidth,
                                gridWidth = slotWidth * slots,
                                onPlay = onPlay,
                                onReminder = onReminder,
                                onFavorite = onFavorite,
                                onGroup = {
                                    groupChannel = channel
                                    groupText = channelId?.let(state.preferences::get)?.customGroup.orEmpty()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    groupChannel?.let { channel ->
        AlertDialog(
            onDismissRequest = { groupChannel = null },
            title = { Text("Custom group") },
            text = {
                OutlinedTextField(
                    value = groupText,
                    onValueChange = { groupText = it.take(60) },
                    label = { Text("Group name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onGroup(channel, groupText.trim().takeIf { it.isNotBlank() })
                    groupChannel = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onGroup(channel, null)
                    groupChannel = null
                }) { Text("Remove group") }
            },
        )
    }
    if (showDiagnostics) {
        DiagnosticsDialog(
            diagnostics = state.diagnostics,
            onClear = onClearDiagnostics,
            onDismiss = { showDiagnostics = false },
        )
    }
    if (showMappings) {
        EpgMappingDialog(
            channels = state.channels,
            programs = state.programs,
            mappings = state.epgMappings,
            selectedChannelId = state.mappingChannelId,
            candidates = state.mappingCandidates,
            loading = state.mappingLoading,
            onSelectChannel = onLoadMapping,
            onApply = onApplyMapping,
            onRemove = onRemoveMapping,
            onDismiss = {
                onDismissMapping()
                showMappings = false
            },
        )
    }
}

@Composable
private fun DiagnosticsDialog(
    diagnostics: List<LivePlaybackDiagnostic>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback diagnostics") },
        text = {
            if (diagnostics.isEmpty()) {
                Text("No IPTV playback attempts recorded yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(diagnostics, key = { it.eventId }) { event ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    event.event,
                                    color = if (event.event == LivePlaybackDiagnostic.EVENT_READY) {
                                        Color(0xFF4CD964)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(formatDiagnosticTime(event.timestamp), fontSize = 11.sp)
                            }
                            Text(event.channelName, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(event.host)
                                    event.quality?.let { append(" · $it") }
                                    event.latencyMs?.let { append(" · ${it}ms") }
                                },
                                fontSize = 12.sp,
                            )
                            event.message?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = diagnostics.isNotEmpty(),
                onClick = {
                    val report = diagnostics.joinToString("\n") { event ->
                        "${formatDiagnosticTime(event.timestamp)} ${event.event} ${event.channelName} " +
                            "host=${event.host} quality=${event.quality ?: "unknown"} " +
                            "latency=${event.latencyMs ?: -1}ms ${event.message.orEmpty()}"
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NexaStream IPTV diagnostics", report))
                },
            ) { Text("Copy report") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear, enabled = diagnostics.isNotEmpty()) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun EpgMappingDialog(
    channels: List<TvShow>,
    programs: List<EpgProgram>,
    mappings: Map<String, EpgChannelMapping>,
    selectedChannelId: String?,
    candidates: List<XmlTvChannel>,
    loading: Boolean,
    onSelectChannel: (TvShow) -> Unit,
    onApply: (TvShow, XmlTvChannel) -> Unit,
    onRemove: (TvShow) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = channels.firstOrNull { it.liveMetadata?.channelId == selectedChannelId }
    val scheduledIds = remember(programs) { programs.map(EpgProgram::channelId).toSet() }
    val orderedChannels = remember(channels, scheduledIds, mappings) {
        channels.sortedWith(
            compareBy<TvShow> { it.liveMetadata?.channelId?.let(scheduledIds::contains) == true }
                .thenBy { it.title.lowercase() },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selected == null) "Repair EPG mapping" else "Map ${selected.title}") },
        text = {
            when {
                loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                selected != null -> {
                    if (candidates.isEmpty()) {
                        Text("No XMLTV channel catalog is cached yet. Refresh the guide, then try again.")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 500.dp)) {
                            items(candidates, key = { "${it.sourceUrl}|${it.xmlTvChannelId}" }) { candidate ->
                                Card(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        onApply(selected, candidate)
                                    },
                                ) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(candidate.displayName, fontWeight = FontWeight.Bold)
                                        Text(candidate.xmlTvChannelId, fontSize = 11.sp)
                                        Text(candidate.sourceUrl.substringBefore('?'), fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.heightIn(max = 500.dp)) {
                    items(orderedChannels, key = { it.id }) { channel ->
                        val channelId = channel.liveMetadata?.channelId.orEmpty()
                        val mapping = mappings[channelId]
                        val hasSchedule = channelId in scheduledIds
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(channel.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    when {
                                        mapping != null -> "Mapped to ${mapping.xmlTvChannelId}"
                                        hasSchedule -> "Schedule matched automatically"
                                        else -> "No schedule — mapping recommended"
                                    },
                                    fontSize = 11.sp,
                                    color = if (!hasSchedule && mapping == null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onSelectChannel(channel) }) { Text("Map") }
                            if (mapping != null) TextButton(onClick = { onRemove(channel) }) { Text("Remove") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RecordingStrip(recordings: List<LiveRecording>, onPlay: (LiveRecording) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DVR", fontWeight = FontWeight.Bold)
        recordings.take(8).forEach { recording ->
            Card(
                Modifier.width(180.dp).clickable(
                    enabled = recording.status == LiveRecording.STATUS_COMPLETED,
                    onClick = { onPlay(recording) },
                ),
            ) {
                Text(
                    if (recording.status == LiveRecording.STATUS_RECORDING) "REC  ${recording.title}" else recording.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideTimeHeader(anchor: Long, channelWidth: Dp, slotWidth: Dp, slots: Int) {
    Row(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.width(channelWidth).height(42.dp), contentAlignment = Alignment.CenterStart) {
            Text("Channels", fontWeight = FontWeight.Bold)
        }
        repeat(slots) { index ->
            Box(Modifier.width(slotWidth).height(42.dp), contentAlignment = Alignment.CenterStart) {
                Text(formatTime(anchor + index * 30 * 60 * 1000L), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GuideChannelRow(
    channel: TvShow,
    preference: LiveChannelPreference?,
    programs: List<EpgProgram>,
    reminderIds: Set<String>,
    anchorMillis: Long,
    durationMillis: Long,
    channelWidth: Dp,
    gridWidth: Dp,
    onPlay: (TvShow) -> Unit,
    onReminder: (TvShow, EpgProgram) -> Unit,
    onFavorite: (TvShow) -> Unit,
    onGroup: () -> Unit,
) {
    Row(Modifier.height(88.dp).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(channelWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onPlay(channel) }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(channel.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(channel.liveMetadata?.quality ?: "LIVE", preference?.customGroup).joinToString(" · "),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { onFavorite(channel) }, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (preference?.isFavorite == true) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Favorite channel",
                    tint = if (preference?.isFavorite == true) Color(0xFFFFC107) else Color.Unspecified,
                )
            }
            IconButton(onClick = onGroup, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Folder, "Custom group")
            }
        }
        Box(
            Modifier.width(gridWidth).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
        ) {
            if (programs.isEmpty()) {
                Text("No schedule data", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            programs.forEach { program ->
                val clippedStart = maxOf(program.startMillis, anchorMillis)
                val clippedEnd = minOf(program.endMillis, anchorMillis + durationMillis)
                val startFraction = (clippedStart - anchorMillis).toFloat() / durationMillis
                val widthFraction = (clippedEnd - clippedStart).toFloat() / durationMillis
                val isNow = program.startMillis <= System.currentTimeMillis() && program.endMillis > System.currentTimeMillis()
                Card(
                    Modifier.offset(x = gridWidth * startFraction)
                        .width((gridWidth * widthFraction).coerceAtLeast(54.dp)).fillMaxHeight()
                        .padding(2.dp).clickable { onPlay(channel) },
                ) {
                    Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                program.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text("${formatTime(program.startMillis)}–${formatTime(program.endMillis)}", fontSize = 10.sp)
                        }
                        if (program.startMillis > System.currentTimeMillis()) {
                            IconButton(onClick = { onReminder(channel, program) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (program.programId in reminderIds) Icons.Default.AlarmOn else Icons.Default.Alarm,
                                    contentDescription = "Programme reminder",
                                    tint = if (program.programId in reminderIds) MaterialTheme.colorScheme.primary
                                    else Color.Unspecified,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun floorToHalfHour(value: Long): Long {
    val halfHour = 30 * 60 * 1000L
    return value - (value % halfHour)
}

private fun formatTime(value: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(value))

private fun formatDiagnosticTime(value: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(value))
