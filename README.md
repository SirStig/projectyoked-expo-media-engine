# @projectyoked/react-native-media-engine

Expo native module for video composition with text/emoji overlays, audio extraction, and waveform generation. Built for high-performance video editing in React Native applications.

## Features

- **Video Composition**: Create videos with text and emoji overlays burned into the video
- **Audio Extraction**: Extract audio tracks from video files
- **Waveform Generation**: Generate amplitude waveforms from audio files
- **Text Overlays**: Add timed text overlays with custom colors, sizes, and positioning
- **Emoji Overlays**: Add timed emoji overlays with custom sizes and positioning
- **Audio Mixing**: Mix original video audio with background music at custom volumes
- **Hardware Accelerated**: Uses native APIs (AVFoundation on iOS, MediaCodec on Android)

## Installation

```bash
npm install @projectyoked/react-native-media-engine
```

or

```bash
yarn add @projectyoked/react-native-media-engine
```

### iOS

After installation, run:

```bash
npx expo prebuild --platform ios --clean
cd ios && pod install
```

### Android  

After installation, run:

```bash
npx expo prebuild --platform android --clean
```

## Requirements

- Expo SDK 54+
- React Native 0.81+
- iOS 13.4+
- Android SDK 26+

## Usage

### Check Module Availability

```javascript
import MediaEngine from '@projectyoked/react-native-media-engine';

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

MIT

## Author

ProjectYoked

## Contributing

https://github.com/SirStig/projectyoked-react-native-media-engine