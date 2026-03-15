# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Validation (lint + typecheck + tests)
npm run validate

# Individual steps
npm run lint          # ESLint
npm run typecheck     # TypeScript (tsc --noEmit)
npm test              # Jest tests
npm run test:coverage # Coverage report
npm run test:watch    # Watch mode

# Single test file
npx jest tests/module.test.js
```

There is no build step — this is a native module distributed as source.

## Architecture

This is `@projectyoked/expo-media-engine`, an Expo native module for hardware-accelerated video composition and editing on iOS and Android.

### Layer Structure

**JavaScript bridge** (`src/`) — thin wrapper over the native module:
- `src/index.js` — loads the native module via `requireNativeModule('MediaEngine')`, exposes 6 async functions, and resolves quality strings (`low/medium/high`) to bitrates (1/4/10 Mbps)
- `src/index.d.ts` — TypeScript definitions for all public types

**iOS** (`ios/`, Swift + AVFoundation):
- `MediaEngineModule.swift` — all async functions: extract audio, waveform, legacy `exportComposition`, and `composeCompositeVideo` (multi-track with CATextLayer overlays, AVMutableComposition, smart passthrough detection to skip re-encoding)
- `VideoStitcher.swift` — concatenates videos via `AVMutableComposition` with Passthrough export (no re-encoding)

**Android** (`android/`, Kotlin + MediaCodec + OpenGL ES 2.0):
- `MediaEngineModule.kt` — Expo module entry; marshals JS data through Record types; extracts video metadata dynamically; dispatches to composer/stitcher; handles rotation-aware dimension swapping
- `CompositeVideoComposer.kt` — OpenGL ES rendering pipeline: `MediaExtractor → MediaCodec (decode) → SurfaceTexture → OpenGL → EGLSurface → MediaCodec (encode) → MediaMuxer`
- `RenderEngine.kt` — decoupled renderer for live previews; manages TextureRenderer pool, decoder pool, MVP matrix transforms, overlay texture caching
- `VideoStitcher.kt` — fast path using `mp4parser` (isoparser 1.1.22) with `AppendTrack`; falls back to transcoding if fast path fails
- `WaveformGenerator.kt` — MediaCodec decode → RMS amplitude per sample bucket → normalized float array
- `AudioMixer.kt` — audio track mixing engine
- `TextureRenderer.kt` — OpenGL ES 2.0 rendering with MVP matrices

### Data Flow for `composeCompositeVideo`

JS config (`CompositionConfig`) → native Record types → composition engine:
- A config has `tracks[]`, each track has a `type` (VIDEO/AUDIO/TEXT) and `clips[]`
- Each clip can have transforms: `position`, `scale`, `rotation`, `resizeMode` (cover/contain/stretch), `startTime`, `duration`
- Text/emoji overlays are timed (appear/disappear) and support color, size, animation

### Key Design Decisions

- **Smart passthrough**: both iOS and Android detect when re-encoding can be skipped (single-clip, no transforms) for performance
- **Feed-and-drain loop**: Android uses codec feed/drain synchronization to keep decoder and encoder in sync
- **Fallback transcoding**: `VideoStitcher` on Android tries fast mp4parser first, falls back to full transcode on failure
- **Quality resolution**: done in JS (`low→1Mbps`, `medium→4Mbps`, `high→10Mbps`) before passing to native

### Testing

Tests mock the native module and validate:
- Module structure (all 6 functions exported)
- Return types (all functions return Promises)
- TypeScript interface compliance

The TypeScript compiler validates types separately via `npm run typecheck`.
