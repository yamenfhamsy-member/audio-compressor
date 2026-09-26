# Audio Compressor

Native Kotlin Android app. Converts audio files (MP3/M4A/WAV/FLAC) to **Opus** (`.opus`) or **Vorbis** (`.ogg`) to reduce size. Offline-first, all processing on-device via FFmpeg.

## Presets

| Preset | Codec | Setting | Typical saving |
|---|---|---|---|
| Music | libopus | 96k stereo 48kHz VBR audio | ~50% |
| Voice | libopus | 24k mono 24kHz VBR voip | ~88% |
| Compat | libvorbis | q4 ogg | ~30% |

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
