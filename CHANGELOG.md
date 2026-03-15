# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
