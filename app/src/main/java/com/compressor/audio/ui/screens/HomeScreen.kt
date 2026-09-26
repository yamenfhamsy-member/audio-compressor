package com.compressor.audio.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compressor.audio.AudioItem
import com.compressor.audio.OutputMode
import com.compressor.audio.Preset
import com.compressor.audio.R
import com.compressor.audio.convertToM4a
import com.compressor.audio.convertToOpus
import com.compressor.audio.copyMp3Stream
import com.compressor.audio.formatBytes
import com.compressor.audio.formatDuration
import com.compressor.audio.outputExtension
import com.compressor.audio.publishToDownloads
import com.compressor.audio.queryAudioTrack
import com.compressor.audio.queryDisplayName
import com.compressor.audio.queryDurationMs
import com.compressor.audio.querySize
import com.compressor.audio.savedPercent
import com.compressor.audio.ui.components.PresetSegment
import com.compressor.audio.ui.components.SectionLabel
import com.compressor.audio.ui.components.VinlandButton
import com.compressor.audio.ui.components.VinlandProgress
import com.compressor.audio.ui.theme.MonoTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class JobState { QUEUED, WORKING, DONE, ERROR }

data class Job(
    val item: AudioItem,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val outBytes: Long = 0,
    val outName: String? = null,
    val error: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
)

private data class PresetDef(val preset: Preset, val title: Int, val sub: Int)
private data class OutputDef(val mode: OutputMode, val title: Int, val sub: Int)

@Composable
fun HomeScreen(lang: String, onToggleLang: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf(listOf<Job>()) }
    var presetIndex by remember { mutableStateOf(0) }
    var outputIndex by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var statusLine by remember { mutableStateOf(context.getString(R.string.status_offline)) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingUri by remember { mutableStateOf<Uri?>(null) }

    val presetDefs = listOf(
        PresetDef(Preset.MUSIC, R.string.preset_music, R.string.preset_music_sub),
        PresetDef(Preset.BALANCED, R.string.preset_balanced, R.string.preset_balanced_sub),
        PresetDef(Preset.VOICE, R.string.preset_voice, R.string.preset_voice_sub),
    )
    val outputDefs = listOf(
        OutputDef(OutputMode.OPUS, R.string.out_opus, R.string.out_opus_sub),
        OutputDef(OutputMode.MP3_COPY, R.string.out_mp3, R.string.out_mp3_sub),
        OutputDef(OutputMode.M4A, R.string.out_m4a, R.string.out_m4a_sub),
    )
    val mp3Compat = jobs.count { it.item.isMp3Audio }

    fun addUris(uris: List<Uri>, isVideo: Boolean) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                val name = queryDisplayName(context.contentResolver, uri)
                val (hasAudio, mime) = queryAudioTrack(context, uri)
                AudioItem(
                    uri = uri,
                    name = name,
                    sizeBytes = querySize(context.contentResolver, uri),
                    durationMs = queryDurationMs(context, uri),
                    isVideo = isVideo,
                    hasAudio = hasAudio,
                    audioMime = mime,
                )
            }
            withContext(Dispatchers.Main) {
                jobs = jobs + items.map { Job(it) }
                statusLine = context.getString(R.string.status_added, items.size)
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> addUris(uris, isVideo = false) }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> addUris(uris, isVideo = true) }

    // Release the preview player when the screen leaves the composition.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            player?.let { p ->
                runCatching { p.stop() }
                p.release()
            }
            player = null
        }
    }

    fun togglePlay(uri: Uri) {
        if (playingUri == uri) {
            player?.let { p ->
                runCatching { p.stop() }
                p.release()
            }
            player = null
            playingUri = null
            return
        }
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        playingUri = null
        statusLine = context.getString(R.string.status_loading)
        scope.launch(Dispatchers.IO) {
            val p = MediaPlayer()
            val ok = runCatching {
                p.setDataSource(context, uri)
                p.prepare()
                p.start()
            }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) {
                    player = p
                    playingUri = uri
                    statusLine = context.getString(R.string.status_playing)
                    p.setOnCompletionListener {
                        runCatching { it.stop() }
                        it.release()
                        if (playingUri == uri) playingUri = null
                        player = null
                    }
                } else {
                    runCatching { p.release() }
                    statusLine = context.getString(R.string.status_play_failed)
                }
            }
        }
    }

    fun runAll() {
        if (running || jobs.isEmpty()) return
        // Snapshot once: files added mid-run must not move the denominator.
        val snapshot = jobs.toList()
        val preset = presetDefs[presetIndex].preset
        val mode = outputDefs[outputIndex].mode
        val skipMsg = context.getString(R.string.skip_not_mp3)
        val noAudioMsg = context.getString(R.string.no_audio)
        running = true
        doneCount = 0
        scope.launch(Dispatchers.IO) {
            snapshot.forEachIndexed { idx, job ->
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.id == job.id) it.copy(state = JobState.WORKING, progress = 0f) else it
                    }
                    statusLine = context.getString(R.string.converting, idx + 1, snapshot.size)
                }
                var ok = false
                var err: String? = null
                var outFile: File? = null
                var mime = "audio/ogg"
                try {
                    if (!job.item.hasAudio) {
                        err = noAudioMsg
                    } else {
                        val base = job.item.name.substringBeforeLast('.').ifBlank { "audio" }
                            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
                        val ext = outputExtension(mode)
                        outFile = File(context.cacheDir, "out_${base}_${job.id.take(6)}.$ext")
                        if (outFile.exists()) outFile.delete()
                        var lastPosted = 0f
                        val progress: (Float) -> Unit = { frac ->
                            if (frac - lastPosted > 0.05f || frac >= 1f) {
                                lastPosted = frac
                                val f = frac
                                scope.launch(Dispatchers.Main) {
                                    jobs = jobs.map {
                                        if (it.id == job.id) it.copy(progress = f) else it
                                    }
                                }
                            }
                        }
                        when (mode) {
                            OutputMode.OPUS -> {
                                convertToOpus(context, job.item.uri, outFile, preset, progress)
                                mime = "audio/ogg"
                            }
                            OutputMode.MP3_COPY -> {
                                if (!job.item.isMp3Audio) {
                                    err = skipMsg
                                } else {
                                    copyMp3Stream(context, job.item.uri, outFile, progress)
                                    mime = "audio/mpeg"
                                }
                            }
                            OutputMode.M4A -> {
                                val ch = preset.channels
                                convertToM4a(
                                    context, job.item.uri, outFile, ch,
                                    if (ch == 1) 64000 else 96000, progress,
                                )
                                mime = "audio/mp4"
                            }
                        }
                        if (err == null) {
                            if (outFile.exists() && outFile.length() > 0) {
                                val pubName = "${base}.$ext"
                                if (publishToDownloads(context, outFile, pubName, mime) != null) {
                                    ok = true
                                } else {
                                    err = context.getString(R.string.status_play_failed)
                                }
                            } else {
                                err = context.getString(R.string.status_failed)
                            }
                        }
                    }
                } catch (e: Exception) {
                    err = e.message?.take(160) ?: context.getString(R.string.st_error)
                }
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.id == job.id) {
                            if (ok) it.copy(
                                state = JobState.DONE,
                                progress = 1f,
                                outBytes = outFile?.length() ?: 0,
                                outName = outFile?.name,
                            )
                            else it.copy(state = JobState.ERROR, error = err)
                        } else it
                    }
                    doneCount++
                    if (doneCount == snapshot.size) {
                        running = false
                        val saved = jobs.filter { it.state == JobState.DONE }
                        statusLine = if (saved.isEmpty()) context.getString(R.string.status_failed)
                        else context.getString(R.string.status_done, saved.size, jobs.size)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoTokens.Canvas),
    ) {
        // AppBar: title + language toggle, 1px blade divider.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoTokens.Canvas)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.title),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
                color = MonoTokens.Bone,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(MonoTokens.Steel)
                    .border(1.dp, MonoTokens.BorderBlade)
                    .clickable(onClick = onToggleLang)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lang_toggle),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MonoTokens.Bone,
                )
            }
        }
        HorizontalDivider(color = MonoTokens.BorderBlade, thickness = 1.dp)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.sec_source))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    VinlandButton(
                        label = if (jobs.isEmpty()) stringResource(R.string.pick_audio)
                        else stringResource(R.string.add_more),
                        onClick = { if (!running) audioPicker.launch("audio/*") },
                        primary = false,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    VinlandButton(
                        label = stringResource(R.string.pick_video),
                        onClick = { if (!running) videoPicker.launch("video/*") },
                        primary = false,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.source_note),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.sec_output))
                Spacer(Modifier.height(8.dp))
                PresetSegment(
                    options = outputDefs.map { stringResource(it.title) },
                    selected = outputIndex,
                    onSelect = { if (!running) outputIndex = it },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(outputDefs[outputIndex].sub),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
                if (outputDefs[outputIndex].mode == OutputMode.MP3_COPY && jobs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.mp3_compat, mp3Compat, jobs.size),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MonoTokens.Ash,
                    )
                }
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.sec_preset))
                Spacer(Modifier.height(8.dp))
                PresetSegment(
                    options = presetDefs.map { stringResource(it.title) },
                    selected = presetIndex,
                    onSelect = { if (!running) presetIndex = it },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(presetDefs[presetIndex].sub),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel(stringResource(R.string.sec_files))
                Spacer(Modifier.height(8.dp))
            }

            if (jobs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MonoTokens.Iron)
                            .border(1.dp, MonoTokens.BorderBlade)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.empty),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = MonoTokens.Muted,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                items(jobs, key = { it.id }) { job ->
                    FileRow(
                        job = job,
                        isPlaying = playingUri == job.item.uri,
                        onPlay = { togglePlay(job.item.uri) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            item {
                if (running) {
                    VinlandProgress(fraction = if (jobs.isEmpty()) 0f else doneCount.toFloat() / jobs.size)
                    Spacer(Modifier.height(8.dp))
                }
                VinlandButton(
                    label = when {
                        running -> stringResource(R.string.converting, doneCount, jobs.size)
                        jobs.isEmpty() -> stringResource(R.string.convert)
                        else -> stringResource(R.string.convert_n, jobs.size)
                    },
                    onClick = { runAll() },
                    enabled = !running && jobs.isNotEmpty(),
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FileRow(job: Job, isPlaying: Boolean, onPlay: () -> Unit) {
    val stateTag = when (job.state) {
        JobState.QUEUED -> stringResource(R.string.st_queued)
        JobState.WORKING -> stringResource(R.string.st_working)
        JobState.DONE -> stringResource(R.string.st_done, savedPercent(job.item.sizeBytes, job.outBytes))
        JobState.ERROR -> stringResource(R.string.st_error)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MonoTokens.Iron)
            .border(1.dp, MonoTokens.BorderBlade)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Chiseled square play affordance, not a round FAB.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(MonoTokens.Steel)
                    .border(1.dp, MonoTokens.BorderBlade)
                    .clickable(onClick = onPlay)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (isPlaying) stringResource(R.string.stop) else stringResource(R.string.play),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MonoTokens.Bone,
                    modifier = Modifier,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = job.item.name,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MonoTokens.Bone,
                    maxLines = 1,
                )
                Text(
                    text = "${formatBytes(job.item.sizeBytes)} - ${formatDuration(job.item.durationMs)}" +
                        (if (job.state == JobState.DONE) " -> ${formatBytes(job.outBytes)}" else ""),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (job.item.isVideo) stringResource(R.string.badge_video)
                    else stringResource(R.string.badge_audio),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MonoTokens.Muted,
                )
                Text(
                    text = stateTag,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when (job.state) {
                        JobState.DONE -> MonoTokens.SuccessText
                        JobState.ERROR -> MonoTokens.ErrorText
                        else -> MonoTokens.Ash
                    },
                )
            }
        }
        if (job.state == JobState.WORKING) {
            Spacer(Modifier.height(8.dp))
            VinlandProgress(fraction = job.progress)
        }
        if (job.state == JobState.ERROR) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = (job.error ?: "").take(160),
                fontFamily = FontFamily.SansSerif,
                fontSize = 11.sp,
                color = MonoTokens.ErrorText,
            )
        }
    }
}
