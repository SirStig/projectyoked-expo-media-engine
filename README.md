# @projectyoked/expo-media-engine

[![npm version](https://badge.fury.io/js/@projectyoked%2Fexpo-media-engine.svg)](https://badge.fury.io/js/@projectyoked%2Fexpo-media-engine)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![CI](https://github.com/SirStig/projectyoked-expo-media-engine/workflows/CI/badge.svg)](https://github.com/SirStig/projectyoked-expo-media-engine/actions)
[![Platform](https://img.shields.io/badge/platform-iOS%20%7C%20Android-lightgrey)](https://github.com/SirStig/projectyoked-expo-media-engine)
[![Expo](https://img.shields.io/badge/Expo-49%2B-blue.svg)](https://expo.dev)

**Professional video composition and editing for Expo apps.** Built with the Expo Modules API for high-performance native video processing with text/emoji overlays, audio extraction, and waveform generation.

## Features

- **Multi-track composition**: `composeCompositeVideo` — video/audio/text tracks with clips, transforms, and timed text/emoji overlays
- **Video stitching**: `stitchVideos` — concatenate multiple videos (passthrough when possible)
- **Video compression**: `compressVideo` — reduce file size, resolution, or bitrate
- **Legacy composition**: `exportComposition` — single-video + text/emoji overlays and audio mixing
- **Audio extraction**: Extract audio tracks from video files
- **Waveform generation**: Generate amplitude waveforms from audio files
- **Hardware accelerated**: AVFoundation (iOS), MediaCodec/OpenGL (Android)
- **Built for Expo**: Native Expo module with full TypeScript support

## Installation

Default install gets the latest stable (0.1.x). To use the alpha (multi-track composition, stitching, compression), install the `alpha` tag:

```bash
# Latest stable (0.1.x)
npm install @projectyoked/expo-media-engine

# Alpha (1.0.0-alpha-1)
npm install @projectyoked/expo-media-engine@alpha
```

or with yarn:

```bash
yarn add @projectyoked/expo-media-engine
yarn add @projectyoked/expo-media-engine@alpha
```

### Setup

After installation, rebuild your Expo app:

```bash
npx expo prebuild
npx expo run:ios    # or run:android
```

> **Note**: This is an **Expo module** built with the Expo Modules API. It requires Expo SDK 49+ and will work in any Expo project (managed or bare workflow).

### iOS

For iOS-specific setup after prebuild:

```bash
cd ios && pod install
```

### Android  

After prebuild, Android should be ready to run.

## Requirements

- **Expo SDK** 49+
- **expo-modules-core** >= 1.0.0  
- React Native 0.64+
- React 16.13+
- iOS 13.4+
- Android SDK 21+ (API level 21)

**Compatible with:**
- ✅ Expo managed workflow
- ✅ Expo bare workflow (after `npx expo prebuild`)
- ✅ Development builds
- ✅ EAS Build

## Usage

### Check Module Availability

```javascript
import MediaEngine from '@projectyoked/expo-media-engine';

if (MediaEngine.isAvailable()) {
  // Module is loaded and ready
}
```

### Extract Audio from Video

```javascript
const audioUri = await MediaEngine.extractAudio(
  videoUri,      // Input video path
  outputUri      // Output audio path (.m4a on iOS, .mp3 on Android)
);
```

### Generate Audio Waveform

```javascript
const waveformData = await MediaEngine.getWaveform(
  audioUri,      // Audio file path
  100            // Number of samples
);
// Returns array of normalized amplitude values [0-1]
```

### Export Video with Overlays

```javascript
const config = {
  videoPath: '/path/to/video.mp4',
  outputPath: '/path/to/output.mp4',
  duration: 10.5,  // Video duration in seconds
  
  // Text overlays
  textArray: ['Hello', 'World'],
  textX: [0.5, 0.5],              // X position (0-1, normalized)
  textY: [0.3, 0.7],              // Y position (0-1, normalized)
  textColors: ['#FFFFFF', '#FF0000'],
  textSizes: [48, 36],
  textStarts: [0, 3],             // Start time in seconds
  textDurations: [3, 5],          // Duration in seconds
  
  // Emoji overlays
  emojiArray: ['🔥', '💪'],
  emojiX: [0.2, 0.8],
  emojiY: [0.5, 0.5],
  emojiSizes: [64, 64],
  emojiStarts: [1, 4],
  emojiDurations: [2, 3],
  
  // Audio mixing
  musicPath: '/path/to/music.mp3',  // Optional background music
  musicVolume: 0.5,                 // Music volume (0-1)
  originalVolume: 0.8,              // Original video audio volume (0-1)
};

const outputPath = await MediaEngine.exportComposition(config);
```

### Stitch Multiple Videos

```javascript
const outputUri = await MediaEngine.stitchVideos(
  ['/path/to/a.mp4', '/path/to/b.mp4'],
  '/path/to/output.mp4'
);
```

### Compress Video

```javascript
const outputUri = await MediaEngine.compressVideo({
  inputUri: '/path/to/video.mp4',
  outputUri: '/path/to/compressed.mp4',
  quality: 'medium',
});
```

## API Reference

### `extractAudio(videoUri: string, outputUri: string): Promise<string>`

Extracts the audio track from a video file.

**Parameters:**
- `videoUri`: Path to the input video file
- `outputUri`: Path for the output audio file

**Returns:** Promise resolving to the output audio file path

---

### `getWaveform(audioUri: string, samples: number): Promise<number[]>`

Generates a waveform from an audio file.

**Parameters:**
- `audioUri`: Path to the audio file
- `samples`: Number of amplitude samples to generate (default: 100)

**Returns:** Promise resolving to array of normalized amplitude values (0-1)

---

### `exportComposition(config: object): Promise<string>`

Creates a video with text/emoji overlays and audio mixing.

**Config Parameters:**
- `videoPath` (string, required): Input video file path
- `outputPath` (string, required): Output video file path
- `duration` (number): Video duration in seconds
- `textArray` (string[]): Array of text strings to overlay
- `textX` (number[]): X positions (0-1, normalized to video width)
- `textY` (number[]): Y positions (0-1, normalized to video height)
- `textColors` (string[]): Hex color codes (e.g., '#FFFFFF')
- `textSizes` (number[]): Font sizes in points
- `textStarts` (number[]): Start times in seconds
- `textDurations` (number[]): Display durations in seconds
- `emojiArray` (string[]): Array of emoji strings
- `emojiX` (number[]): X positions
- `emojiY` (number[]): Y positions
- `emojiSizes` (number[]): Emoji sizes in points
- `emojiStarts` (number[]): Start times in seconds
- `emojiDurations` (number[]): Display durations in seconds
- `musicPath` (string): Path to background music file
- `musicVolume` (number): Background music volume (0-1)
- `originalVolume` (number): Original video audio volume (0-1)

**Returns:** Promise resolving to the output video file path

---

### `composeCompositeVideo(config: CompositionConfig): Promise<string>`

Multi-track composition: video/audio/text tracks with clips, transforms (position, scale, rotation, resizeMode), and timed text/emoji overlays. Use for multi-clip timelines; supports smart passthrough when re-encoding is not needed.

**Returns:** Promise resolving to the output video file path

---

### `stitchVideos(videoPaths: string[], outputUri: string): Promise<string>`

Concatenates multiple videos into one. Uses passthrough on iOS; on Android uses fast path when possible with transcoding fallback.

**Returns:** Promise resolving to the output video file path

---

### `compressVideo(config: object): Promise<string>`

Compresses a video (bitrate/resolution). Config: `inputUri`, `outputUri`, optional `quality` ('low' | 'medium' | 'high') or `bitrate`.

**Returns:** Promise resolving to the output video file path

---

### `isAvailable(): boolean`

Checks if the native module is properly loaded.

**Returns:** `true` if module is available, `false` otherwise

## Platform Differences

### iOS
- Uses AVFoundation for video processing
- Audio output format: M4A
- Supports all overlay features

### Android
- Uses MediaCodec for video processing
- Audio output format: MP3
- Supports all overlay features

## Performance

- Video processing is hardware-accelerated on both platforms
- Text/emoji overlays are burned directly into the video
- Typical processing speed: ~1x realtime (10 second video in ~10 seconds)

## Development & Testing

### Running Tests

```bash
# Run all tests
npm test

# Run tests with coverage
npm run test:coverage

# Run tests in watch mode
npm run test:watch

# Run linting
npm run lint

# Run TypeScript type checking
npm run typecheck

# Run all validation checks
npm run validate
```

### Building for Development

This is a native module, so no JavaScript build step is required. For testing in a real app:

**Expo Project:**
```bash
npx expo prebuild
npx expo run:ios    # or run:android
```

**Bare React Native:**
```bash
npx pod-install     # iOS
# Android auto-links
```

### Publishing

Alpha releases are published under the `alpha` dist-tag so `latest` stays on the current stable (0.1.x). After bumping version to e.g. `1.0.0-alpha-1`:

```bash
npm run validate
npm run publish:alpha
```

Users get stable with `npm install @projectyoked/expo-media-engine` and alpha with `npm install @projectyoked/expo-media-engine@alpha`.

### TypeScript Support

This package includes TypeScript definitions. Import with full type safety:

```typescript
import MediaEngine, { ExportCompositionConfig } from '@projectyoked/expo-media-engine';

const config: ExportCompositionConfig = {
  videoPath: '/path/to/video.mp4',
  outputPath: '/path/to/output.mp4',
  // ... TypeScript will autocomplete and validate all options
};
```

## Error Handling

```javascript
try {
  const output = await MediaEngine.exportComposition(config);
} catch (error) {
  console.error('Export failed:', error.message);
}
```

Common errors:
- `"MediaEngine unavailable"`: Module not loaded (check installation)
- Invalid file paths
- Unsupported video formats
- Insufficient device storage

## License

MIT © [ProjectYoked](https://github.com/SirStig/projectyoked-expo-media-engine)

See [LICENSE](LICENSE) for more information.

## Contributing

Contributions are welcome! Please read our [Contributing Guide](CONTRIBUTING.md) for details on our code of conduct and the process for submitting pull requests.

## Security

For security issues, please see our [Security Policy](SECURITY.md).

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history.

## Links

- [npm Package](https://www.npmjs.com/package/@projectyoked/expo-media-engine)
- [GitHub Repository](https://github.com/SirStig/projectyoked-expo-media-engine)
- [Issues](https://github.com/SirStig/projectyoked-expo-media-engine/issues)
- [Pull Requests](https://github.com/SirStig/projectyoked-expo-media-engine/pulls)
- [Expo Documentation](https://docs.expo.dev)

## Support

If you like this project, please consider:
- ⭐ Starring the repository
- 🐛 Reporting bugs
- 💡 Suggesting new features
- 🤝 Contributing code

---

Made with ❤️ by ProjectYoked | Built with Expo Modules API