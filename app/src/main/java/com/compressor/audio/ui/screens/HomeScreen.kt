package com.compressor.audio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import com.compressor.audio.CloudSplit
import com.compressor.audio.CloudStt
import com.compressor.audio.copyUriToCache
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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class JobState { QUEUED, WORKING, DONE, ERROR }

/**
 * A finished file the user owns: either a shared Downloads Uri, or — when
 * the shared publish failed verification — a plain file in the app folder.
 * One of [uri]/[file] is always set. Nothing is ever reported "saved"
 * unless one of them holds real bytes.
 */
data class KeptFile(val name: String, val uri: Uri? = null, val file: File? = null)

data class Job(
    val item: AudioItem,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val outBytes: Long = 0,
    val outName: String? = null,
    val error: String? = null,
    /** Optional DONE label override (e.g. split output file names). */
    val note: String? = null,
    /** Transcribed speech text (section 06). */
    val transcript: String? = null,
    /** Which tab produced this result: 0 compress, 1 split, 2 transcribe. */
    val origin: Int = -1,
    /** Finished files owned by this job (1 for compress, 2 for split). */
    val keptFiles: List<KeptFile> = emptyList(),
    /** True if any kept file fell back to the app folder. */
    val fellBack: Boolean = false,
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
    // Feature state lives up here so every runner can see every other one.
    var tabIndex by remember { mutableStateOf(0) }
    var splitting by remember { mutableStateOf(false) }
    val splitCancel = remember { AtomicBoolean(false) }
    var transcribing by remember { mutableStateOf(false) }
    val sttCancel = remember { AtomicBoolean(false) }
    val busy = running || splitting || transcribing
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sectionTitles = listOf(
        stringResource(R.string.tab_compress),
        stringResource(R.string.tab_split),
        stringResource(R.string.tab_stt),
    )

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

    /**
     * App-specific folder for finished files: the real SD card when the
     * phone has one, otherwise internal app storage. Needs no permissions.
     * Returns the folder plus whether it sits on a removable card.
     */
    fun sdFolder(): Pair<File, Boolean> {
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val target = dirs.elementAtOrNull(1) ?: dirs.elementAtOrNull(0)
            ?: context.filesDir
        val folder = File(target, "ThorfinAudio")
        runCatching { if (!folder.exists()) folder.mkdirs() }
        return Pair(folder, dirs.size > 1)
    }

    /**
     * Keep [file] under [displayName]: first try shared Downloads (verified),
     * and if that fails keep a real copy in the app folder so the user's
     * work is never lost. Never returns null without bytes behind it.
     */
    fun keepFile(file: File, displayName: String, mime: String): KeptFile? {
        val uri = runCatching { publishToDownloads(context, file, displayName, mime) }.getOrNull()
        if (uri != null) return KeptFile(displayName, uri = uri)
        val (folder, _) = sdFolder()
        val dest = File(folder, displayName)
        runCatching { file.copyTo(dest, overwrite = true) }
        if (dest.exists() && dest.length() > 0) return KeptFile(displayName, file = dest)
        return null
    }

    /**
     * Copy every finished result of tab [kind] into the app SD folder.
     * Multi-file features (split = vocals + instrumental) copy everything.
     */
    fun exportToSd(kind: Int) {
        val targets = jobs.filter { it.state == JobState.DONE && it.origin == kind }
        if (targets.isEmpty()) {
            statusLine = context.getString(R.string.sd_empty)
            return
        }
        statusLine = context.getString(R.string.cloud_download)
        scope.launch(Dispatchers.IO) {
            val (folder, onSd) = sdFolder()
            var count = 0
            for (job in targets) {
                if (kind == 2) {
                    val text = job.transcript ?: continue
                    val base = job.item.name.substringBeforeLast('.').ifBlank { "audio" }
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
                    runCatching {
                        File(folder, "${base}.txt").writeText(text)
                        count++
                    }
                } else {
                    for (kf in job.keptFiles) {
                        runCatching {
                            val ins = when {
                                kf.uri != null -> context.contentResolver.openInputStream(kf.uri)
                                kf.file != null -> kf.file.inputStream()
                                else -> null
                            } ?: throw IllegalStateException("no source")
                            ins.use { src ->
                                File(folder, kf.name).outputStream().use { outs -> src.copyTo(outs) }
                            }
                            count++
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                statusLine = if (count > 0) {
                    context.getString(
                        if (onSd) R.string.sd_saved else R.string.sd_saved_internal, count,
                    )
                } else {
                    context.getString(R.string.status_failed)
                }
            }
        }
    }

    fun runAll() {
        if (running || splitting || transcribing || jobs.isEmpty()) return
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
                var keptFiles = emptyList<KeptFile>()
                var fellBack = false
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
                                val kept = keepFile(outFile, pubName, mime)
                                if (kept != null) {
                                    ok = true
                                    keptFiles = listOf(kept)
                                    fellBack = kept.file != null
                                } else {
                                    err = context.getString(R.string.status_failed)
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
                                origin = 0,
                                keptFiles = keptFiles,
                                fellBack = fellBack,
                                note = if (fellBack) context.getString(
                                    R.string.saved_fallback,
                                    keptFiles.joinToString(", ") { kf -> kf.name },
                                ) else null,
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

    // ---- Vocal split (cloud only) ----

    fun extOf(item: AudioItem): String {
        val fromName = item.name.substringAfterLast('.', "").lowercase()
            .replace(Regex("[^a-z0-9]"), "")
        if (fromName.isNotEmpty() && fromName.length <= 5) return fromName
        return if (item.isVideo) "mp4" else "mp3"
    }

    /** Translate a cloud failure into words a human would actually say. */
    fun cloudErr(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            "upload" in m || "litterbox" in m || "uguu" in m ->
                context.getString(R.string.err_upload)
            "cloud busy" in m ->
                context.getString(R.string.err_cloud_busy)
            "run " in m || "run not found" in m || "run lost" in m ->
                context.getString(R.string.err_run)
            "artifact" in m || "transcript missing" in m || "vocals missing" in m ->
                context.getString(R.string.err_artifact)
            else -> context.getString(R.string.st_error)
        }
    }

    fun runSplitCloud() {
        if (splitting || running || transcribing || jobs.isEmpty()) return
        val snapshot = jobs.toList()
        val cancelledMsg = context.getString(R.string.split_cancelled)
        val noAudioMsg = context.getString(R.string.no_audio)
        splitting = true
        splitCancel.set(false)
        doneCount = 0
        scope.launch(Dispatchers.IO) {
            val phaseUpload = context.getString(R.string.cloud_uploading)
            val phaseQueued = context.getString(R.string.cloud_queued)
            val phaseWorking = context.getString(R.string.cloud_working)
            val phaseDownload = context.getString(R.string.cloud_download)
            snapshot.forEachIndexed { idx, job ->
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.id == job.id) it.copy(state = JobState.WORKING, progress = 0f) else it
                    }
                    statusLine = context.getString(R.string.splitting, idx + 1, snapshot.size)
                }
                var ok = false
                var err: String? = null
                var names = ""
                var keptFiles = emptyList<KeptFile>()
                var fellBack = false
                try {
                    if (!job.item.hasAudio) {
                        err = noAudioMsg
                    } else {
                        val ext = extOf(job.item)
                        val src = copyUriToCache(context, job.item.uri, ext, job.id.take(6))
                        fun post(frac: Float) {
                            scope.launch(Dispatchers.Main) {
                                jobs = jobs.map {
                                    if (it.id == job.id) it.copy(progress = frac) else it
                                }
                            }
                        }
                        val stems = CloudSplit.splitFile(
                            context, src, ext, job.id.replace("-", "").take(12),
                            onProgress = { frac, phase ->
                                val label = when (phase) {
                                    "upload" -> phaseUpload
                                    "queued", "dispatch" -> phaseQueued
                                    "working" -> phaseWorking
                                    else -> phaseDownload
                                }
                                scope.launch(Dispatchers.Main) {
                                    jobs = jobs.map {
                                        if (it.id == job.id) it.copy(progress = frac) else it
                                    }
                                    statusLine = "$label ${((frac) * 100).toInt()}%"
                                }
                            },
                            isCancelled = { splitCancel.get() },
                        )
                        runCatching { src.delete() }
                        val base = job.item.name.substringBeforeLast('.').ifBlank { "audio" }
                            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
                        val vName = "${base}_vocals.wav"
                        val mName = "${base}_instrumental.wav"
                        val v = keepFile(stems.vocals, vName, "audio/wav")
                        val m = keepFile(stems.instrumental, mName, "audio/wav")
                        runCatching { stems.vocals.delete() }
                        runCatching { stems.instrumental.delete() }
                        keptFiles = listOfNotNull(v, m)
                        fellBack = keptFiles.any { it.file != null }
                        if (keptFiles.isNotEmpty()) {
                            ok = true
                            names = if (keptFiles.size == 2 && !fellBack) {
                                context.getString(R.string.split_saved, vName, mName)
                            } else {
                                context.getString(
                                    R.string.saved_fallback,
                                    keptFiles.joinToString(", ") { it.name },
                                )
                            }
                        } else {
                            err = context.getString(R.string.status_failed)
                        }
                    }
                } catch (e: CancellationException) {
                    err = cancelledMsg
                } catch (e: Exception) {
                    err = cloudErr(e)
                }
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.id == job.id) {
                            if (ok) it.copy(
                                state = JobState.DONE, progress = 1f,
                                outBytes = 0, outName = names, note = names,
                                origin = 1, keptFiles = keptFiles, fellBack = fellBack,
                            )
                            else it.copy(state = JobState.ERROR, error = err)
                        } else it
                    }
                    doneCount++
                    if (names.isNotEmpty() && ok) statusLine = names
                    if (doneCount == snapshot.size || splitCancel.get()) {
                        splitting = false
                        if (splitCancel.get()) statusLine = cancelledMsg
                    }
                }
                if (splitCancel.get()) return@forEachIndexed
            }
            withContext(Dispatchers.Main) { splitting = false }
        }
    }

    // ---- Speech to text (section 06, cloud only) ----

    fun runTranscribe() {
        if (transcribing || running || splitting || jobs.isEmpty()) return
        val snapshot = jobs.toList()
        val cancelledMsg = context.getString(R.string.stt_cancelled)
        val noAudioMsg = context.getString(R.string.no_audio)
        val emptyMsg = context.getString(R.string.stt_empty)
        transcribing = true
        sttCancel.set(false)
        doneCount = 0
        scope.launch(Dispatchers.IO) {
            snapshot.forEachIndexed { idx, job ->
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.id == job.id) it.copy(state = JobState.WORKING, progress = 0f) else it
                    }
                    statusLine = context.getString(R.string.transcribing, idx + 1, snapshot.size)
                }
                var err: String? = null
                var text: String? = null
                try {
                    if (!job.item.hasAudio) {
                        err = noAudioMsg
                    } else {
                        val ext = extOf(job.item)
                        val src = copyUriToCache(context, job.item.uri, ext, job.id.take(6))
                        fun post(frac: Float) {
                            scope.launch(Dispatchers.Main) {
                                jobs = jobs.map {
                                    if (it.id == job.id) it.copy(progress = frac) else it
                                }
                            }
                        }
                        try {
                            val out = CloudStt.transcribe(
                                context, src, "ar",
                                job.id.replace("-", "").take(12),
                                onProgress = { frac, _ -> post(frac) },
                                isCancelled = { sttCancel.get() },
                            )
                            if (out.isBlank()) err = emptyMsg else text = out
                        } finally {
                            runCatching { src.delete() }
                        }
                        post(1f)
                    }
                } catch (e: CancellationException) {
                    err = cancelledMsg
                } catch (e: Exception) {
                    err = cloudErr(e)
                }
                withContext(Dispatchers.Main) {
                    jobs = jobs.map {
                        if (it.id == job.id) {
                            if (text != null) it.copy(
                                state = JobState.DONE, progress = 1f, transcript = text,
                                origin = 2,
                            )
                            else it.copy(state = JobState.ERROR, error = err)
                        } else it
                    }
                    doneCount++
                    if (doneCount == snapshot.size || sttCancel.get()) {
                        transcribing = false
                        if (sttCancel.get()) statusLine = cancelledMsg
                    }
                }
                if (sttCancel.get()) return@forEachIndexed
            }
            withContext(Dispatchers.Main) { transcribing = false }
        }
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    fun saveTxt(job: Job) {
        val text = job.transcript ?: return
        scope.launch(Dispatchers.IO) {
            val base = job.item.name.substringBeforeLast('.').ifBlank { "audio" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            val file = File(context.cacheDir, "${base}.txt")
            runCatching { file.writeText(text) }
            val kept = keepFile(file, "${base}.txt", "text/plain")
            withContext(Dispatchers.Main) {
                statusLine = if (kept == null) {
                    context.getString(R.string.status_failed)
                } else if (kept.file != null) {
                    context.getString(R.string.saved_fallback, "${base}.txt")
                } else {
                    context.getString(R.string.saved_txt, "${base}.txt")
                }
            }
        }
    }

    @Composable
    fun SdButton(kind: Int) {
        val ready = jobs.count {
            it.state == JobState.DONE && it.origin == kind &&
                (it.keptFiles.isNotEmpty() || it.transcript != null)
        }
        if (ready > 0) {
            Spacer(Modifier.height(8.dp))
            VinlandButton(
                label = stringResource(R.string.sd_save, ready),
                onClick = { exportToSd(kind) },
                primary = false,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !busy,
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(264.dp)
                    .background(MonoTokens.Iron)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.drawer_title),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    color = MonoTokens.Muted,
                )
                Spacer(Modifier.height(16.dp))
                sectionTitles.forEachIndexed { i, name ->
                    val active = i == tabIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(0.dp))
                            .background(if (active) MonoTokens.Pineapple else MonoTokens.Steel)
                            .then(
                                if (!active) Modifier.border(1.dp, MonoTokens.BorderBlade)
                                else Modifier,
                            )
                            .clickable {
                                tabIndex = i
                                scope.launch { drawerState.close() }
                            }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = name,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (active) MonoTokens.OnPineapple else MonoTokens.Bone,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoTokens.Canvas),
    ) {
        // AppBar: burger + two-tone title + language toggle, 1px blade divider.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoTokens.Canvas)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(MonoTokens.Steel)
                    .border(1.dp, MonoTokens.BorderBlade)
                    .clickable { scope.launch { drawerState.open() } }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    repeat(3) { line ->
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(2.dp)
                                .background(MonoTokens.Pineapple),
                        )
                        if (line < 2) Spacer(Modifier.height(4.dp))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val titleParts = stringResource(R.string.title).split(" ", limit = 2)
                Text(
                    text = titleParts[0],
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 1.sp,
                    color = MonoTokens.Bone,
                )
                if (titleParts.size > 1) {
                    Text(
                        text = " " + titleParts[1],
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        letterSpacing = 1.sp,
                        color = MonoTokens.Leaf,
                    )
                }
            }
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
                        onClick = { if (!busy) audioPicker.launch("audio/*") },
                        primary = false,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    VinlandButton(
                        label = stringResource(R.string.pick_video),
                        onClick = { if (!busy) videoPicker.launch("video/*") },
                        primary = false,
                        enabled = !busy,
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
                Spacer(Modifier.height(8.dp))
                if (tabIndex == 0) {
                    SectionLabel(stringResource(R.string.sec_output))
                    Spacer(Modifier.height(8.dp))
                    PresetSegment(
                        options = outputDefs.map { stringResource(it.title) },
                        selected = outputIndex,
                        onSelect = { if (!busy) outputIndex = it },
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
                        onSelect = { if (!busy) presetIndex = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(presetDefs[presetIndex].sub),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = MonoTokens.Ash,
                    )
                    Spacer(Modifier.height(16.dp))
                }
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
                        onCopy = { job.transcript?.let { copyText(it) } },
                        onSaveTxt = { saveTxt(job) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (tabIndex == 0) {
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
                        enabled = !busy && jobs.isNotEmpty(),
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
                    SdButton(kind = 0)
                    Spacer(Modifier.height(8.dp))
                }
            } else if (tabIndex == 1) {
                item {
                    SectionLabel(stringResource(R.string.sec_split))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.split_cloud_note),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = MonoTokens.Ash,
                    )
                    Spacer(Modifier.height(8.dp))
                    VinlandButton(
                        label = if (splitting) stringResource(R.string.split_cancel)
                        else if (jobs.isEmpty()) stringResource(R.string.split_btn)
                        else stringResource(R.string.split_n, jobs.size),
                        onClick = {
                            if (splitting) splitCancel.set(true) else runSplitCloud()
                        },
                        primary = true,
                        enabled = jobs.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = statusLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MonoTokens.Ash,
                    )
                    SdButton(kind = 1)
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                item {
                    SectionLabel(stringResource(R.string.sec_stt))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.stt_cloud_note),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = MonoTokens.Ash,
                    )
                    Spacer(Modifier.height(8.dp))
                    VinlandButton(
                        label = if (transcribing) stringResource(R.string.stt_cancel)
                        else if (jobs.isEmpty()) stringResource(R.string.stt_btn)
                        else stringResource(R.string.stt_n, jobs.size),
                        onClick = {
                            if (transcribing) sttCancel.set(true) else runTranscribe()
                        },
                        primary = true,
                        enabled = jobs.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = statusLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MonoTokens.Ash,
                    )
                    SdButton(kind = 2)
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.made_by),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = MonoTokens.Muted,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
private fun FileRow(
    job: Job,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onCopy: () -> Unit,
    onSaveTxt: () -> Unit,
) {
    val stateTag = when (job.state) {
        JobState.QUEUED -> stringResource(R.string.st_queued)
        JobState.WORKING -> stringResource(R.string.st_working)
        JobState.DONE -> job.note
            ?: stringResource(R.string.st_done, savedPercent(job.item.sizeBytes, job.outBytes))
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
        if (job.transcript != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = job.transcript,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MonoTokens.Bone,
                maxLines = 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniButton(
                    label = stringResource(R.string.copy_text),
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                MiniButton(
                    label = stringResource(R.string.save_txt),
                    onClick = onSaveTxt,
                    modifier = Modifier.weight(1f),
                )
            }
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
/** Small chiseled action button for row-level actions (copy / save). */
@Composable
private fun MiniButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(MonoTokens.Steel)
            .border(1.dp, MonoTokens.BorderBlade)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MonoTokens.Bone,
        )
    }
}

