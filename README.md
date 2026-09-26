# Audio Compressor

Native Kotlin Android app. Converts audio files (MP3/M4A/WAV/FLAC) to **Opus** (`.opus`) to reduce size. Offline-first, all processing on-device with **zero native dependencies**:

1. `MediaExtractor` + `MediaCodec` (built into Android) decode the source to PCM.
2. A small linear resampler brings everything to 48 kHz.
3. `Concentus` (pure-JVM Opus port) encodes 20 ms frames.
4. A hand-rolled Ogg muxer (`OggOpusWriter`, RFC 3533 + RFC 7845) writes the file.

No FFmpeg, no NDK — the APK stays tiny (~4 MB) and CI builds in minutes.

## Presets

| Preset | Codec | Setting | Typical saving |
|---|---|---|---|
| Music | Opus | 96k stereo | ~50% |
| Balanced | Opus | 64k stereo | ~65% |
| Voice | Opus | 24k mono voip | ~88% |

Note: MP3 -> Opus is lossy-to-lossy. Quality cannot be restored; the goal is smaller size at acceptable quality.

## Design

UI follows `vinland-design-system/` geometry (1px hairlines, radii 0/2/4, dense lists) adapted to strict mono gray: `#08090B / #111318 / #181B22 / #E6E4DD / #828997`. No gradients, no blur, no pills, no decorative color.

## Build

Everything builds on GitHub Actions. No local SDK needed:

1. Push to `main` triggers `Build APK`.
2. Download the `app-release` artifact (unsigned release APK, fine for testing).

```
gh run watch --exit-status
gh run download --name app-release
```
