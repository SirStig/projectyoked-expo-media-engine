# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha-3] - 2026-03-23

### Fixed

- **Android `extractAudio` missing** — `extractAudio(videoUri, outputUri)` was fully implemented on iOS but absent from the Android module, causing a runtime error on Android whenever the function was called. Android now remuxes the audio track directly from the source container into an M4A file using `MediaExtractor` + `MediaMuxer` (no re-encoding, lossless, fast). Both platforms now have complete API parity.

---

## [1.0.0-alpha-2] - 2026-03-23

### Added

#### Live Preview Engine
- **`MediaEnginePreview` component** — Native Expo view that renders a `CompositionConfig` in real-time at ~30 fps using the same hardware-accelerated pipeline as the export engine. What you preview is what you export.
  - **Android** (`PreviewEngine.kt`, `MediaEnginePreviewView.kt`): Sets up its own EGL context bound to a `TextureView`, runs a `HandlerThread` render loop calling `RenderEngine.render(timeUs)` → `eglSwapBuffers`. Fully applies all filters, transitions, speed, opacity, and image tracks. Audio via `MediaPlayer` synchronized to render time.
  - **iOS** (`MediaEnginePreviewView.swift`): Builds the same `AVMutableComposition` + `MediaEngineCompositor` pipeline used for export and hands it to `AVPlayer` + `AVPlayerLayer` for frame-accurate hardware playback.
  - Props: `config`, `isPlaying`, `muted`, `currentTime` (controlled scrub position in seconds)
  - Events: `onLoad({ duration })`, `onTimeUpdate({ currentTime })`, `onPlaybackEnded()`, `onError({ message })`
  - Ref handle: `seekTo(seconds)` for imperative seeking

- **`useCompositionOverlays(config, currentTime)` hook** — Returns all `text` and `image` clips active at the given playback position with transforms fully resolved. Designed for Skia / Reanimated interactive overlay layers in a CapCut-style editor.
  - Same normalized coordinate space as the export engine (x/y 0..1, scale multiplier, rotation degrees) — no coordinate conversion needed
  - Keyframe-animated `x`, `y`, `scale`, `rotation`, `opacity` interpolated with linear easing
  - Returns full text styling (color, fontSize, fontWeight, shadow, stroke, background) and image URI
  - Sorted by track index for correct z-ordering

#### Filters (both platforms)
- 5 new filter types: `brightness`, `contrast`, `saturation`, `warm`, `cool` (alongside existing `grayscale`, `sepia`, `vignette`, `invert`)
- All filters accept a `filterIntensity` (0..1) parameter
- Android: implemented in GLSL fragment shader in `TextureRenderer`
- iOS: implemented via `CIFilter` chains in the new `MediaEngineCompositor` custom compositor

#### Transitions (both platforms)
- 8 transition types: `crossfade`, `fade`, `slide-left`, `slide-right`, `slide-up`, `slide-down`, `zoom-in`, `zoom-out`
- iOS: custom `AVVideoCompositing` compositor (`MediaEngineCompositor` + `MediaEngineInstruction`) with per-frame `CIFilter` blending
- Android: per-frame opacity / position / scale modulation in `RenderEngine.render()`

#### Per-clip Properties (both platforms)
- `opacity` — per-clip transparency 0..1
- `speed` — playback speed multiplier (0.5 = slow-mo, 2.0 = fast-forward)
- `transitionDuration` — explicit transition window in seconds
- `volumeEnvelope.keyframes` — time-based volume automation with linear interpolation between keyframes

#### Text Overlay Styling (both platforms)
- `textFontBold` — bold typeface
- `textBackgroundColor` / `textBackgroundPadding` — colored pill behind text
- `textShadowColor` / `textShadowRadius` / `textShadowOffsetX` / `textShadowOffsetY` — drop shadow
- `textStrokeColor` / `textStrokeWidth` — text outline / stroke

#### Image Tracks
- `CompositeTrack` type `image` — image clips with position, scale, rotation, opacity, and timed visibility on both platforms

#### Compression
- `compressVideo`: `codec` option (`h264` | `h265`), `maxWidth` / `maxHeight` constraints, explicit `width` / `height` targets

### Fixed

#### Both Platforms
- **Volume keyframe parsing** — `volumeEnvelope.keyframes` was in the TypeScript API but never wired to native. Android: added `VolumeKeyframeRecord` / `VolumeEnvelopeRecord` nested Record types in `MediaEngineModule`. iOS: reads `clip["volumeEnvelope"]["keyframes"]` and calls `setVolumeRamp` per consecutive pair for sample-accurate automation.
- **Text clip opacity** — `addTimingAnimation` was always called with `maxOpacity: 1.0` for text and background layers. Now reads `clip["opacity"]` and passes it through.

#### Android
- **`transitionDuration` ignored** — Field was stored in `Clip` but `RenderEngine` never used it; transitions always spanned the full clip overlap. Now computes window as `outgoing.end - transitionDuration`.
- **`WaveformGenerator` file descriptor leak** — `extractor.release()` was missing in the `setDataSource` catch block.
- **`AudioMixer` time formula** — Incorrect `bufferSize * usPerSample * channelCount / 2` corrected to `bufferSize * usPerSample`.
- **`AudioMixer` single-clip bypass** — Bypass gate now checks `clipStart == 0.0` and fade durations, preventing incorrect pass-through for trimmed or faded clips.
- **`RenderEngine` image-only composition crash** — Guard changed from `if (extractors.isEmpty())` to `if (hasVideoTracks && extractors.isEmpty())`.
- **`RenderEngine` per-frame image disk reads** — Image dimensions were decoded from disk every frame. Fixed by caching `ImageTexture(texId, width, height)` on first load.
- **H.264 encoder compatibility** — Added `KEY_LEVEL = AVCLevel41` to the encoder `MediaFormat`.
- **`canSmartStitch` URI ordering** — Now sorts clips by `startTime` before comparing URIs to input order.

#### iOS
- **Text stroke** — `strokeColor` / `strokeWidth` were parsed but never applied. Now rendered via `NSAttributedString` with `.strokeColor` / `.strokeWidth`, matching Android parity.
- **Text background padding** — Was hardcoded to `insetBy(dx: -8, dy: -4)`; now reads `clip["backgroundPadding"]`.
- **`getWaveform` OOM on large files** — Single `AVAudioPCMBuffer(frameCapacity: totalFrames)` replaced with a chunked 8192-frame streaming loop.
- **Smart passthrough with image tracks** — Passthrough was incorrectly activating for compositions with image tracks. Added `imageTracks.isEmpty` guard.

### Changed
- **TypeScript** — `FilterType` extended with 5 new values; `TransitionType` union added; `VolumeEnvelope.keyframes` promoted from reserved to documented; added `ClipAnimations`, `ActiveOverlay`, `MediaEnginePreviewProps`, `MediaEnginePreviewRef` interfaces
- **`index.js`** — Added try/catch around `getWaveform`; exports `MediaEnginePreview` and `useCompositionOverlays` from package root
- **`jest.config.js`** — Transform pattern extended to cover `.jsx` files

## [1.0.0-alpha-1] - 2026-03-14

### Added
- **Multi-track composition** via `composeCompositeVideo`: video/audio/text tracks with clips, transforms (position, scale, rotation, resizeMode), and timed text/emoji overlays
- **Video stitching** via `stitchVideos`: concatenate multiple videos (passthrough on iOS; mp4parser fast path with transcoding fallback on Android)
- **Video compression** via `compressVideo`: reduce file size, resolution, or bitrate (replacement for react-native-compressor in post-render flows)
- Smart passthrough on both platforms when re-encoding can be skipped (single-clip, no transforms)
- Quality presets resolved in JS: `low` (1 Mbps), `medium` (4 Mbps), `high` (10 Mbps)

### Note
This is an alpha release. APIs may change before 1.0.0. Prefer `composeCompositeVideo` for new multi-clip compositions; `exportComposition` remains supported for the legacy single-video + overlays workflow.

## [0.1.3] - 2025-12-29

### Changed
- **BREAKING**: Renamed package from `@projectyoked/react-native-media-engine` to `@projectyoked/expo-media-engine`
- **BREAKING**: Package now explicitly requires Expo SDK 49+
- Made Expo SDK a required peer dependency (no longer optional)
- Updated all documentation to reflect Expo-focused positioning
- Clarified that this is built with Expo Modules API
- Removed bare React Native installation instructions
- Package now clearly communicates it's an Expo module

### Added
- Comprehensive test suite with Jest
- TypeScript type definitions
- ESLint and Babel configuration
- GitHub Actions CI/CD workflows
- Production-ready package configuration
- LICENSE, CONTRIBUTING.md, SECURITY.md, CHANGELOG.md files

## [0.1.2] - 2025-12-29

### Changed
- Updated package.json with proper dependencies and metadata
- Added comprehensive documentation
- Improved repository configuration for production

## [0.1.1] - 2025-12

### Added
- Initial release
- Video composition with text overlays
- Video composition with emoji overlays
- Audio extraction from video files
- Waveform generation from audio files
- Audio mixing capabilities
- Support for iOS (AVFoundation) and Android (MediaCodec)

### Features
- Hardware-accelerated video processing
- Customizable text/emoji positioning and timing
- Volume control for audio mixing
- Normalized waveform data output

## [0.1.0] - 2025-12

### Added
- Initial development version
