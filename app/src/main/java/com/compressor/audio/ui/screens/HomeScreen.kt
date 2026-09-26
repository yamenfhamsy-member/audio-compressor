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
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compressor.audio.AudioItem
import com.compressor.audio.Preset
import com.compressor.audio.convertToOpus
import com.compressor.audio.formatBytes
import com.compressor.audio.formatDuration
import com.compressor.audio.outputExtension
import com.compressor.audio.publishToDownloads
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
)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf(listOf<Job>()) }
    var presetIndex by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    var statusLine by remember { mutableStateOf("OFFLINE - FILES NEVER LEAVE THIS DEVICE") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingUri by remember { mutableStateOf<Uri?>(null) }

    val presets = Preset.entries.toList()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                val name = queryDisplayName(context.contentResolver, uri)
                AudioItem(
                    uri = uri,
                    name = name,
                    sizeBytes = querySize(context.contentResolver, uri),
                    durationMs = queryDurationMs(context, uri),
                )
            }
            withContext(Dispatchers.Main) {
                jobs = jobs + items.map { Job(it) }
                statusLine = "${items.size} FILE(S) ADDED"
            }
        }
    }

    fun togglePlay(uri: Uri) {
        if (playingUri == uri) {
            player?.stop(); player?.release(); player = null; playingUri = null
            return
        }
        player?.stop(); player?.release()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(context, uri)
            p.prepare()
            p.start()
            player = p
            playingUri = uri
            p.setOnCompletionListener { playingUri = null }
        }.onFailure {
            statusLine = "PLAYBACK FAILED"
        }
    }

    fun runAll() {
        if (running || jobs.isEmpty()) return
        running = true
        doneCount = 0
        val preset = presets[presetIndex]
        scope.launch(Dispatchers.IO) {
            jobs.toList().forEachIndexed { idx, job ->
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.item.uri == job.item.uri) it.copy(state = JobState.WORKING) else it
                    }
                    statusLine = "CONVERTING ${idx + 1}/${jobs.size} - ${preset.title.uppercase()}"
                }
                var ok = false
                var err: String? = null
                var outFile: File? = null
                try {
                    val base = job.item.name.substringBeforeLast('.').ifBlank { "audio" }
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
                    outFile = File(context.cacheDir, "out_${base}.${outputExtension(preset)}")
                    if (outFile.exists()) outFile.delete()
                    var lastPosted = 0f
                    convertToOpus(context, job.item.uri, outFile, preset) { frac ->
                        // throttle Main-thread posts to ~5% steps
                        if (frac - lastPosted > 0.05f || frac >= 1f) {
                            lastPosted = frac
                            val f = frac
                            scope.launch(Dispatchers.Main) {
                                jobs = jobs.map {
                                    if (it.item.uri == job.item.uri) it.copy(progress = f) else it
                                }
                            }
                        }
                    }
                    if (outFile.exists() && outFile.length() > 0) {
                        val pubName = "${base}.${outputExtension(preset)}"
                        publishToDownloads(context, outFile, pubName)
                        ok = true
                    } else {
                        err = "encoder produced no output"
                    }
                } catch (e: Exception) {
                    err = e.message?.take(160) ?: "failed"
                }
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.item.uri == job.item.uri) {
                            if (ok) it.copy(
                                state = JobState.DONE,
                                outBytes = outFile?.length() ?: 0,
                                outName = outFile?.name,
                            )
                            else it.copy(state = JobState.ERROR, error = err)
                        } else it
                    }
                    doneCount++
                    if (doneCount == jobs.size) {
                        running = false
                        val saved = jobs.filter { it.state == JobState.DONE }
                        statusLine = if (saved.isEmpty()) "ALL FAILED - TRY ANOTHER FILE"
                        else "${saved.size}/${jobs.size} DONE - SAVED TO DOWNLOAD/AUDIOCOMPRESSOR"
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
        // AppBar: 56px + 1px blade divider, uppercase spaced title. No logo art.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoTokens.Canvas)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "AUDIO COMPRESSOR",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
                color = MonoTokens.Bone,
            )
        }
        Divider(color = MonoTokens.BorderBlade, thickness = 1.dp)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionLabel("01 - Source files")
                Spacer(Modifier.height(8.dp))
                VinlandButton(
                    label = if (jobs.isEmpty()) "Pick audio files" else "Add more files",
                    onClick = { picker.launch("audio/*") },
                    primary = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "MP3 / M4A / WAV / FLAC accepted. Lossy sources are re-encoded; quality cannot be restored.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("02 - Preset")
                Spacer(Modifier.height(8.dp))
                PresetSegment(
                    options = presets.map { it.title },
                    selected = presetIndex,
                    onSelect = { presetIndex = it },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = presets[presetIndex].subtitle,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MonoTokens.Ash,
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("03 - Output")
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
                            text = "NO FILES YET",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = MonoTokens.Muted,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                items(jobs, key = { it.item.uri.toString() }) { job ->
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
                        running -> "Converting $doneCount/${jobs.size}"
                        jobs.isEmpty() -> "Convert"
                        else -> "Convert ${jobs.size} file(s)"
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
        JobState.QUEUED -> "QUEUED"
        JobState.WORKING -> "WORKING"
        JobState.DONE -> "DONE -${savedPercent(job.item.sizeBytes, job.outBytes)}%"
        JobState.ERROR -> "ERROR"
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
                    text = if (isPlaying) "STOP" else "PLAY",
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
        if (job.state == JobState.WORKING) {
            Spacer(Modifier.height(8.dp))
            VinlandProgress(fraction = job.progress)
        }
        if (job.state == JobState.ERROR) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = (job.error ?: "failed").take(160),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MonoTokens.ErrorText,
            )
        }
    }
}
