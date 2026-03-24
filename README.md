# @projectyoked/expo-media-engine

[![npm](https://img.shields.io/npm/v/@projectyoked/expo-media-engine.svg)](https://www.npmjs.com/package/@projectyoked/expo-media-engine)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![CI](https://github.com/SirStig/projectyoked-expo-media-engine/workflows/CI/badge.svg)](https://github.com/SirStig/projectyoked-expo-media-engine/actions)

A hardware-accelerated video composition and editing engine for Expo. Built on AVFoundation (iOS) and MediaCodec + OpenGL ES 2.0 (Android), it gives you a full editing pipeline — real-time preview, multi-track composition, filters, transitions, audio mixing, and export — without proprietary SDKs or per-minute billing.

---

## Features

- **Real-time preview** — Native `<MediaEnginePreview>` view renders your composition at ~30 fps using the exact same pipeline as the export engine, so what you see is what you get
- **Multi-track composition** — Stack video, audio, image, and text tracks with per-clip transforms, timing, and blending
- **9 filters** — Grayscale, sepia, vignette, invert, brightness, contrast, saturation, warm, cool — all with adjustable intensity
- **8 transitions** — Crossfade, fade to black, slide (left/right/up/down), zoom in/out — with configurable duration
- **Per-clip controls** — Opacity, playback speed, source trimming, position, scale, rotation, resize mode (cover/contain/stretch)
- **Audio mixing** — Multiple audio tracks with per-clip volume, fade in/out, and keyframe-based volume automation
- **Text & emoji overlays** — Full styling: font size, bold, color, shadow, stroke, background pill, timed visibility
- **Image overlays** — Positioned, scaled, rotated, and timed image tracks
- **Video stitching** — Fast concatenation using mp4parser (Android) or passthrough (iOS), with transcoding fallback
- **Video compression** — Re-encode with H.264 or H.265, target bitrate/resolution, or quality presets
- **Waveform generation** — Normalized RMS amplitude array from any audio file
- **Audio extraction** — Pull audio from video to `.m4a`

---

## Installation

```bash
npm install @projectyoked/expo-media-engine
npx expo prebuild
```

The current pre-release line (for example `1.0.0-alpha-3`) is what npm installs by default. To stay on the legacy **0.1.x** stable line, pin the version:

```bash
npm install @projectyoked/expo-media-engine@0.1.3
```

This package requires a native build. Expo Go is not supported — use a [development build](https://docs.expo.dev/develop/development-builds/introduction/).

**Requirements**

| | Minimum |
|---|---|
| Expo SDK | 49+ |
| expo-modules-core | 1.0.0+ |
| iOS | 13.4+ |
| Android | API 21+ |
| React Native | 0.64+ |

---

## Quick Start

### Export a composition

```javascript
import MediaEngine from '@projectyoked/expo-media-engine';

const outputUri = await MediaEngine.composeCompositeVideo({
  outputUri: `${FileSystem.cacheDirectory}output.mp4`,
  width: 1080,
  height: 1920,
  frameRate: 30,
  quality: 'high', // 'low' | 'medium' | 'high', or set bitrate explicitly

  tracks: [
    {
      type: 'video',
      clips: [
        {
          uri: 'file:///path/to/clip-a.mp4',
          startTime: 0,
          duration: 5,
          filter: 'warm',
          filterIntensity: 0.6,
        },
        {
          uri: 'file:///path/to/clip-b.mp4',
          startTime: 4,      // overlaps clip-a by 1s for the transition
          duration: 5,
          transition: 'crossfade',
          transitionDuration: 1,
        },
      ],
    },
    {
      type: 'audio',
      clips: [
        {
          uri: 'file:///path/to/music.mp3',
          startTime: 0,
          duration: 9,
          volume: 0.8,
          fadeOutDuration: 1.5,
        },
      ],
    },
    {
      type: 'text',
      clips: [
        {
          text: 'Hello World',
          startTime: 0.5,
          duration: 3,
          x: 0.5,
          y: 0.15,
          textStyle: {
            fontSize: 52,
            fontWeight: 'bold',
            color: '#FFFFFF',
            shadowColor: '#000000',
            shadowRadius: 4,
          },
        },
      ],
    },
  ],
});
```

### Real-time preview

The preview view renders the video layer natively. Text, image, and emoji overlays are returned by `useCompositionOverlays` so your Skia or gesture layer can make them interactive — same coordinate space, no translation required.

```javascript
import { useRef, useState } from 'react';
import { View, StyleSheet } from 'react-native';
import { MediaEnginePreview, useCompositionOverlays } from '@projectyoked/expo-media-engine';

export function CompositionEditor({ config }) {
  const previewRef = useRef(null);
  const [currentTime, setCurrentTime] = useState(0);
  const [isPlaying, setIsPlaying]     = useState(false);

  // Active text/image clips at the current playback position,
  // with resolved x/y/scale/rotation/opacity — feed directly to Skia
  const overlays = useCompositionOverlays(config, currentTime);

  return (
    <View style={styles.container}>
      {/* Native video layer — filters, transitions, speed all applied here */}
      <MediaEnginePreview
        ref={previewRef}
        config={config}
        isPlaying={isPlaying}
        onTimeUpdate={e => setCurrentTime(e.nativeEvent.currentTime)}
        onLoad={e => console.log('Duration:', e.nativeEvent.duration)}
        style={StyleSheet.absoluteFill}
      />

      {/* Interactive overlay layer — render with Skia, Reanimated, or plain RN */}
      {overlays.map(overlay => (
        <InteractiveOverlay
          key={overlay.id}
          overlay={overlay}
          onMove={(id, x, y) => { /* update config */ }}
        />
      ))}
    </View>
  );
}
```

### Other operations

```javascript
import MediaEngine from '@projectyoked/expo-media-engine';

// Stitch videos end-to-end
await MediaEngine.stitchVideos(
  ['file:///clip1.mp4', 'file:///clip2.mp4'],
  'file:///output.mp4'
);

// Compress (e.g. before upload)
await MediaEngine.compressVideo({
  inputUri:  'file:///input.mp4',
  outputUri: 'file:///compressed.mp4',
  quality:   'medium',   // or set bitrate / maxWidth / maxHeight explicitly
  codec:     'h265',     // Android only; iOS uses H.264
});

// Waveform for a timeline scrubber
const amplitudes = await MediaEngine.getWaveform('file:///audio.mp3', 200);

// Extract audio from video
await MediaEngine.extractAudio('file:///video.mp4', 'file:///audio.m4a');
```

---

## API Reference

### `MediaEngine` (default export)

| Function | Description |
|---|---|
| `composeCompositeVideo(config)` | Render a multi-track composition to a video file |
| `stitchVideos(paths, outputUri)` | Concatenate videos sequentially |
| `compressVideo(config)` | Re-encode a video at lower bitrate or resolution |
| `getWaveform(uri, samples?)` | Normalized amplitude array (0–1) for audio visualization |
| `extractAudio(videoUri, outputUri)` | Extract audio track to `.m4a` |
| `exportComposition(config)` | Legacy single-video + overlay export |
| `isAvailable()` | Returns `false` if the native module is not linked |

### `MediaEnginePreview` (named export)

A native view component. Renders video tracks with all filters and transitions applied.

| Prop | Type | Description |
|---|---|---|
| `config` | `CompositionConfig` | The composition to render |
| `isPlaying` | `boolean` | Play / pause |
| `muted` | `boolean` | Mute audio |
| `currentTime` | `number` | Seek position in seconds (controlled scrub while paused) |
| `onLoad` | `({ duration })` | Fired once the engine is ready |
| `onTimeUpdate` | `({ currentTime })` | Fires ~30 fps during playback |
| `onPlaybackEnded` | `()` | Fired when playback reaches the end |
| `onError` | `({ message })` | Fired on fatal errors |

Ref: `previewRef.current.seekTo(seconds)` for imperative seeking.

### `useCompositionOverlays(config, currentTime)`

Returns `ActiveOverlay[]` — the text and image clips active at `currentTime`, with all transforms and keyframe animations resolved. Use this to render an interactive overlay layer in Skia, Reanimated, or plain React Native views.

Each `ActiveOverlay` includes: `id`, `type`, `x`, `y`, `scale`, `rotation`, `opacity`, `text`, `textStyle`, `uri`, `startTime`, `duration`, and all individual text style fields.

---

## Clip Properties

All clip types share these base properties:

| Property | Type | Description |
|---|---|---|
| `uri` | `string` | File URI (`file://...`) |
| `startTime` | `number` | When this clip appears on the timeline (seconds) |
| `duration` | `number` | How long it plays (seconds) |
| `x` / `y` | `number` | Normalized position 0–1 (center of clip) |
| `scale` | `number` | Size multiplier (1.0 = original) |
| `rotation` | `number` | Degrees, clockwise |
| `opacity` | `number` | Transparency 0–1 |
| `clipStart` | `number` | Trim start within the source file (seconds) |
| `clipEnd` | `number` | Trim end within the source file (-1 = full) |
| `speed` | `number` | Playback speed (0.5 = slow-mo, 2.0 = fast-forward) |
| `resizeMode` | `string` | `'cover'` \| `'contain'` \| `'stretch'` |
| `filter` | `FilterType` | One of 9 filter types |
| `filterIntensity` | `number` | Filter strength 0–1 |
| `transition` | `TransitionType` | Transition applied at the end of this clip |
| `transitionDuration` | `number` | Transition window in seconds |
| `volume` | `number` | Audio volume 0–1 (audio/video clips) |
| `fadeInDuration` | `number` | Audio fade in (seconds) |
| `fadeOutDuration` | `number` | Audio fade out (seconds) |
| `volumeEnvelope` | `VolumeEnvelope` | Keyframe-based volume automation |
| `animations` | `ClipAnimations` | Keyframe arrays for x, y, scale, rotation, opacity |

Text clips additionally accept `text`, `textStyle` (or flat `color`, `fontSize`, etc.).

---

## Architecture

```
JavaScript (src/)
    │
    ├── composeCompositeVideo()   ─────────────────────────────────┐
    ├── MediaEnginePreview             ← native view (ExpoView)    │
    └── useCompositionOverlays         ← JS hook (overlay data)   │
                                                                   │
iOS (AVFoundation)                    Android (MediaCodec + OpenGL ES 2.0)
    │                                     │
    ├── AVMutableComposition             ├── CompositeVideoComposer
    ├── MediaEngineCompositor            │       MediaExtractor → MediaCodec (decode)
    │   (CIFilter per frame)            │       → SurfaceTexture → OpenGL (render)
    ├── AVPlayer (preview)              │       → MediaCodec (encode) → MediaMuxer
    └── AVAssetExportSession            ├── RenderEngine  (preview + export render logic)
        (export)                        ├── TextureRenderer  (GLSL shaders, filters)
                                        ├── AudioMixer  (PCM mixing, volume keyframes)
                                        └── PreviewEngine  (EGL loop, 30 fps playback)
```

**Smart passthrough** — both platforms detect when re-encoding can be skipped (single clip, no transforms, no filters) and use a zero-copy path for maximum speed.

---

## Development

```bash
npm run validate        # lint + typecheck + tests
npm run lint            # ESLint only
npm run typecheck       # TypeScript (tsc --noEmit)
npm test                # Jest
npm run test:coverage   # coverage report
```

There is no build step — the package is distributed as source.

---

## Background

Developed at [Project Yoked LLC](https://www.projectyoked.com) for the Project Yoked fitness app. We open-sourced it under MIT so other teams can use the same native media stack without proprietary SDKs or per-minute video APIs.

<p align="left"><a href="https://www.projectyoked.com"><img src="https://projectyoked.com/assets/project-yoked-logo.png" alt="Project Yoked" width="240"></a></p>

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). All contributions are welcome.

## Security

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for a full history of changes by release.

## License

MIT © Project Yoked LLC
