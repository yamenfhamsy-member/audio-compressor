# Thorfin Audio World

Native Kotlin Android app. Extract audio from **video** or compress **audio** files.
Three outputs, all on-device, zero native dependencies:

| Output | Mechanism |
|---|---|
| Compressed (`.opus`) | MediaCodec decode → 48 kHz resample → Concentus Opus → hand-rolled Ogg muxer |
| Original MP3 (`.mp3`) | Verbatim stream copy, only when the source audio is MP3 (instant, lossless) |
| M4A (`.m4a`) | MediaCodec decode → AAC-LC encode → MediaMuxer |

UI is Arabic by default with an EN toggle (RTL supported), Vinland mono-gray style.

## Vocal split (offline)

Splits a song into `*_vocals.wav` + `*_instrumental.wav` using UVR-MDX-NET-Voc_FT
(MIT weights from the Ultimate Vocal Remover project) via ONNX Runtime on-device,
with a host STFT/iSTFT pipeline. The 64 MB model downloads once from GitHub
Releases, then works fully offline. STFT runs on pure-JVM FFT (JTransforms).

## Presets (Opus and M4A)

| Preset | Opus setting | M4A setting | Typical saving |
|---|---|---|---|
| Music | 96k stereo | 96k stereo AAC | ~50% |
| Balanced | 64k stereo | 96k stereo AAC | ~65% |
| Voice | 24k mono voip | 64k mono AAC | ~88% |

Note: MP3 -> Opus is lossy-to-lossy. Quality cannot be restored; the goal is smaller size at acceptable quality.

Known limits: resampling uses linear interpolation without a low-pass prefilter, so
downsampling high-rate sources (96/88.2 kHz) can alias slightly. 44.1/48 kHz sources
(the common case) are unaffected.

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
