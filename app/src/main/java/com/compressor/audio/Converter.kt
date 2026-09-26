package com.compressor.audio

/** Compression presets. Opus first, Vorbis only as compat fallback. */
enum class Preset(val title: String, val subtitle: String) {
    MUSIC("Music", "Opus 96k stereo 48kHz"),
    VOICE("Voice", "Opus 24k mono 24kHz voip"),
    COMPAT("Compat", "Vorbis q4 ogg"),
}

/** FFmpeg args per preset. Input is a local file path, output is a local file path. */
fun ffmpegArgs(preset: Preset, inputPath: String, outputPath: String): Array<String> =
    when (preset) {
        Preset.MUSIC -> arrayOf(
            "-y", "-i", inputPath, "-vn",
            "-c:a", "libopus", "-b:a", "96k", "-vbr", "on",
            "-ar", "48000", "-ac", "2", "-application", "audio",
            outputPath,
        )
        Preset.VOICE -> arrayOf(
            "-y", "-i", inputPath, "-vn",
            "-c:a", "libopus", "-b:a", "24k", "-vbr", "on",
            "-ar", "24000", "-ac", "1", "-application", "voip",
            outputPath,
        )
        Preset.COMPAT -> arrayOf(
            "-y", "-i", inputPath, "-vn",
            "-c:a", "libvorbis", "-q:a", "4",
            "-ar", "44100", "-ac", "2",
            outputPath,
        )
    }

fun outputExtension(preset: Preset): String = when (preset) {
    Preset.MUSIC, Preset.VOICE -> "opus"
    Preset.COMPAT -> "ogg"
}
