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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.nexastream.app.live.LiveTvNavigator
import com.nexastream.app.models.*
import com.nexastream.app.ui.theme.Nexastream2Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
                    onFocus = viewModel::setFocusedProgram
                )
            }
        }
    }
}

@Composable
private fun ProgramInfoPanel(program: EpgProgram?, channel: TvShow?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        if (program != null && channel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = channel.poster,
                    contentDescription = null,
                    modifier = Modifier
                        .width(80.dp)
                        .fillMaxHeight()
                        .background(Color.DarkGray),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatTime(program.startMillis)} - ${formatTime(program.endMillis)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = program.description ?: "No description available.",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Highlight a program to see details",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
    onFocus: (EpgProgram?, TvShow?) -> Unit,
) {
    val now = System.currentTimeMillis()
    val visibleDuration = 6 * 60 * 60 * 1000L // 6 hours visible window
    val minimum = remember { floorToHalfHour(now - 2 * 60 * 60 * 1000L) }
    val maximum = remember { floorToHalfHour(now + 18 * 60 * 60 * 1000L) }
    var anchorMillis by remember { mutableLongStateOf(floorToHalfHour(now)) }
    var filter by remember { mutableStateOf("all") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showMappings by remember { mutableStateOf(false) }
    var groupChannel by remember { mutableStateOf<TvShow?>(null) }
    var groupText by remember { mutableStateOf("") }
    
    val channelWidth = 200.dp
    val slotWidth = 200.dp
    val slots = 12 // 30min slots * 12 = 6 hours
    val gridWidth = slotWidth * slots
    
    val horizontal = rememberScrollState()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
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
        Column(Modifier.fillMaxSize()) {
            // 1. Top Info Panel
            ProgramInfoPanel(state.focusedProgram, state.focusedChannel)

            // 2. Control Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { anchorMillis = (anchorMillis - 2 * 60 * 60 * 1000L).coerceAtLeast(minimum) }) {
                    Icon(Icons.Default.ArrowBack, null)
                    Text("Earlier")
                }
                Button(onClick = { 
                    val timeNow = System.currentTimeMillis()
                    anchorMillis = floorToHalfHour(timeNow)
                    scope.launch { horizontal.animateScrollTo(0) }
                }) {
                    Icon(Icons.Default.LiveTv, null)
                    Text("Now")
                }
                Button(onClick = { anchorMillis = (anchorMillis + 2 * 60 * 60 * 1000L).coerceAtMost(maximum) }) {
                    Text("Later")
                    Icon(Icons.Default.ArrowForward, null)
                }
                
                Spacer(Modifier.weight(1f))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                
                IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Default.Refresh, "Refresh guide")
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // 3. Main Grid Container
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    var currentMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(60_000)
                            currentMillis = System.currentTimeMillis()
                        }
                    }

                    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
                        stickyHeader {
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                                Box(Modifier.width(channelWidth).height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Text("CHANNELS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Box(Modifier.weight(1f).horizontalScroll(horizontal, enabled = false)) {
                                    GuideTimeHeader(anchorMillis, slotWidth, slots)
                                }
                            }
                        }

                        items(visibleChannels, key = { it.id }) { channel ->
                            val channelId = channel.liveMetadata?.channelId
                            val programs = programsByChannel[channelId].orEmpty().filter {
                                it.endMillis > anchorMillis && it.startMillis < anchorMillis + visibleDuration
                            }
                            GuideChannelRow(
                                channel = channel,
                                preference = channelId?.let(state.preferences::get),
                                programs = programs,
                                anchorMillis = anchorMillis,
                                durationMillis = visibleDuration,
                                channelWidth = channelWidth,
                                gridWidth = gridWidth,
                                horizontalScrollState = horizontal,
                                onPlay = onPlay,
                                onFavorite = onFavorite,
                                onGroup = {
                                    groupChannel = channel
                                    groupText = channelId?.let(state.preferences::get)?.customGroup.orEmpty()
                                },
                                onFocus = { onFocus(it, channel) }
                            )
                        }
                    }
                    
                    // Vertical "NOW" line
                    if (currentMillis in anchorMillis..(anchorMillis + visibleDuration)) {
                        val progress = (currentMillis - anchorMillis).toFloat() / visibleDuration
                        val lineOffset = channelWidth + (gridWidth * progress) - horizontal.value.dp
                        
                        Box(
                            Modifier.offset(x = lineOffset)
                                .fillMaxHeight()
                                .width(2.dp)
                                .background(Color.Red.copy(alpha = 0.7f))
                        )
                    }
                }
            }
        }
    }

    // Dialogs...
    if (showDiagnostics) {
        DiagnosticsDialog(state.diagnostics, onClearDiagnostics, { showDiagnostics = false })
    }
    if (showMappings) {
        EpgMappingDialog(state.channels, state.programs, state.epgMappings, state.mappingChannelId, state.mappingCandidates, state.mappingLoading, onLoadMapping, onApplyMapping, onRemoveMapping, { onDismissMapping(); showMappings = false })
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
}

@Composable
private fun GuideTimeHeader(anchor: Long, slotWidth: Dp, slots: Int) {
    Row {
        repeat(slots) { index ->
            Box(Modifier.width(slotWidth).height(40.dp), contentAlignment = Alignment.Center) {
                Text(formatTime(anchor + index * 30 * 60 * 1000L), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GuideChannelRow(
    channel: TvShow,
    preference: LiveChannelPreference?,
    programs: List<EpgProgram>,
    anchorMillis: Long,
    durationMillis: Long,
    channelWidth: Dp,
    gridWidth: Dp,
    horizontalScrollState: ScrollState,
    onPlay: (TvShow) -> Unit,
    onFavorite: (TvShow) -> Unit,
    onGroup: () -> Unit,
    onFocus: (EpgProgram?) -> Unit,
) {
    Row(Modifier.height(90.dp).padding(vertical = 1.dp)) {
        Row(
            Modifier.width(channelWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onPlay(channel) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(model = channel.poster, contentDescription = null, modifier = Modifier.size(40.dp).background(Color.Gray), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(channel.liveMetadata?.quality ?: "LIVE", preference?.customGroup).joinToString(" · "), fontSize = 10.sp, maxLines = 1)
            }
        }
        
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .horizontalScroll(horizontalScrollState)
                .requiredWidth(gridWidth)
        ) {
            programs.forEach { program ->
                val clippedStart = maxOf(program.startMillis, anchorMillis)
                val clippedEnd = minOf(program.endMillis, anchorMillis + durationMillis)
                val startFraction = (clippedStart - anchorMillis).toFloat() / durationMillis
                val widthFraction = (clippedEnd - clippedStart).toFloat() / durationMillis
                val isNow = program.startMillis <= System.currentTimeMillis() && program.endMillis > System.currentTimeMillis()
                
                Card(
                    modifier = Modifier.offset(x = gridWidth * startFraction)
                        .width((gridWidth * widthFraction).coerceAtLeast(60.dp)).fillMaxHeight()
                        .padding(1.dp)
                        .onFocusChanged { if (it.isFocused) onFocus(program) }
                        .clickable { onPlay(channel) },
                ) {
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                        Text(program.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                        Text("${formatTime(program.startMillis)}–${formatTime(program.endMillis)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(diagnostics: List<LivePlaybackDiagnostic>, onClear: () -> Unit, onDismiss: () -> Unit) {
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
                                Text(event.event, color = if (event.event == LivePlaybackDiagnostic.EVENT_READY) Color(0xFF4CD964) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(formatDiagnosticTime(event.timestamp), fontSize = 11.sp)
                            }
                            Text(event.channelName, fontWeight = FontWeight.SemiBold)
                            Text("${event.host} · ${event.quality ?: "unknown"} · ${event.latencyMs ?: -1}ms", fontSize = 12.sp)
                            event.message?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = diagnostics.isNotEmpty(), onClick = {
                val report = diagnostics.joinToString("\n") { "${formatDiagnosticTime(it.timestamp)} ${it.event} ${it.channelName} host=${it.host} quality=${it.quality ?: "unknown"} latency=${it.latencyMs ?: -1}ms ${it.message.orEmpty()}" }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NexaStream IPTV diagnostics", report))
            }) { Text("Copy report") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear, enabled = diagnostics.isNotEmpty()) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
private fun EpgMappingDialog(channels: List<TvShow>, programs: List<EpgProgram>, mappings: Map<String, EpgChannelMapping>, selectedChannelId: String?, candidates: List<XmlTvChannel>, loading: Boolean, onSelectChannel: (TvShow) -> Unit, onApply: (TvShow, XmlTvChannel) -> Unit, onRemove: (TvShow) -> Unit, onDismiss: () -> Unit) {
    val selected = channels.firstOrNull { it.liveMetadata?.channelId == selectedChannelId }
    val scheduledIds = remember(programs) { programs.map(EpgProgram::channelId).toSet() }
    val orderedChannels = remember(channels, scheduledIds, mappings) {
        channels.sortedWith(compareBy<TvShow> { it.liveMetadata?.channelId?.let(scheduledIds::contains) == true }.thenBy { it.title.lowercase() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selected == null) "Repair EPG mapping" else "Map ${selected.title}") },
        text = {
            if (loading) Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (selected != null) {
                if (candidates.isEmpty()) Text("No XMLTV channel catalog is cached yet. Refresh the guide, then try again.")
                else LazyColumn(Modifier.heightIn(max = 500.dp)) {
                    items(candidates, key = { "${it.sourceUrl}|${it.xmlTvChannelId}" }) { candidate ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onApply(selected, candidate) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(candidate.displayName, fontWeight = FontWeight.Bold)
                                Text(candidate.xmlTvChannelId, fontSize = 11.sp)
                                Text(candidate.sourceUrl.substringBefore('?'), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            } else LazyColumn(Modifier.heightIn(max = 500.dp)) {
                items(orderedChannels, key = { it.id }) { channel ->
                    val channelId = channel.liveMetadata?.channelId.orEmpty()
                    val mapping = mappings[channelId]
                    val hasSchedule = channelId in scheduledIds
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(channel.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(if (mapping != null) "Mapped to ${mapping.xmlTvChannelId}" else if (hasSchedule) "Schedule matched automatically" else "No schedule — mapping recommended", fontSize = 11.sp, color = if (!hasSchedule && mapping == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onSelectChannel(channel) }) { Text("Map") }
                        if (mapping != null) TextButton(onClick = { onRemove(channel) }) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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
