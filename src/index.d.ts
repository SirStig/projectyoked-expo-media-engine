/**
 * Type definitions for @projectyoked/expo-media-engine
 */

export interface TextOverlay {
    text: string;
    x: number; // 0-1, normalized position
    y: number; // 0-1, normalized position
    color: string; // hex color code
    size: number; // font size in points
    start: number; // start time in seconds
    duration: number; // duration in seconds
}

export interface EmojiOverlay {
    emoji: string;
    x: number; // 0-1, normalized position
    y: number; // 0-1, normalized position
    size: number; // emoji size in points
    start: number; // start time in seconds
    duration: number; // duration in seconds
}

export interface ExportCompositionConfig {
    videoPath: string;
    outputPath: string;

    // Quality Control
    quality?: 'low' | 'medium' | 'high';
    bitrate?: number;
    videoBitrate?: number;
    audioBitrate?: number;
    enablePassthrough?: boolean;
    videoProfile?: 'baseline' | 'main' | 'high';

    duration?: number;

    // Text overlays (parallel arrays)
    textArray?: string[];
    textX?: number[];
    textY?: number[];
    textColors?: string[];
    textSizes?: number[];
    textStarts?: number[];
    textDurations?: number[];

    // Emoji overlays (parallel arrays)
    emojiArray?: string[];
    emojiX?: number[];
    emojiY?: number[];
    emojiSizes?: number[];
    emojiStarts?: number[];
    emojiDurations?: number[];

    // Audio mixing
    musicPath?: string;
    musicVolume?: number; // 0-1
    originalVolume?: number; // 0-1
}

export interface VolumeEnvelope {
    fadeInDuration?: number;
    fadeOutDuration?: number;
    keyframes?: Array<{ time: number; volume: number }>;
}

export interface TextStyle {
    color?: string;
    fontSize?: number;
    fontWeight?: 'normal' | 'bold';
    backgroundColor?: string;
    backgroundPadding?: number;
    shadowColor?: string;
    shadowRadius?: number;
    shadowOffsetX?: number;
    shadowOffsetY?: number;
    strokeColor?: string;
    strokeWidth?: number;
}

export interface CompositeClip {
    uri: string;
    startTime: number;
    duration: number;
    filter?: 'grayscale' | 'sepia' | 'vignette' | 'invert' | null;
    filterIntensity?: number; // 0..1
    transition?: string | null;
    resizeMode?: 'cover' | 'contain' | 'stretch';
    x?: number;
    y?: number;
    scale?: number;
    rotation?: number;
    volume?: number;
    // Source trimming
    clipStart?: number;  // Trim start in source (seconds)
    clipEnd?: number;    // Trim end in source (seconds, -1 = full)
    // Visual
    opacity?: number;    // 0..1
    // Audio fade envelope
    volumeEnvelope?: VolumeEnvelope;
    // Text/image content (for text/image track types)
    text?: string;
    textStyle?: TextStyle;
}

export interface CompositeTrack {
    type: 'video' | 'audio' | 'text' | 'image';
    clips: CompositeClip[];
}

export interface CompositionConfig {
    outputUri: string;
    width: number;
    height: number;
    frameRate: number;
    bitrate?: number;
    videoBitrate?: number;
    audioBitrate?: number;
    enablePassthrough?: boolean;
    videoProfile?: 'baseline' | 'main' | 'high';
    quality?: 'low' | 'medium' | 'high';
    tracks: CompositeTrack[];
}

export interface CompressVideoConfig {
    inputUri: string;
    outputUri: string;
    /** Target width in px. Height auto-calculated if not set. */
    width?: number;
    height?: number;
    /** Constrain dimensions without specifying exact size */
    maxWidth?: number;
    maxHeight?: number;
    /** Quality tier — maps to sensible presets (low=1Mbps, medium=4Mbps, high=8Mbps) */
    quality?: 'low' | 'medium' | 'high';
    /** Explicit video bitrate in bps. Overrides quality. */
    bitrate?: number;
    audioBitrate?: number;
    frameRate?: number;
    /** Android: H.265 uses ~40% less storage than H.264 at the same quality */
    codec?: 'h264' | 'h265';
}

export interface MediaEngineInterface {
    /**
     * Extract audio from a video file
     * @param videoUri Path to the input video file
     * @param outputUri Path for the output audio file (.m4a on iOS, .mp3 on Android)
     * @returns Promise resolving to the output audio file path
     */
    extractAudio(videoUri: string, outputUri: string): Promise<string>;

    /**
     * Generate amplitude waveform from an audio file
     * @param audioUri Path to the audio file
     * @param samples Number of amplitude samples to generate (default: 100)
     * @returns Promise resolving to array of normalized amplitude values (0-1)
     */
    getWaveform(audioUri: string, samples?: number): Promise<number[]>;

    /**
     * Export a video composition with text/emoji overlays and audio mixing
     * @param config Configuration object for the composition
     * @returns Promise resolving to the output video file path
     */
    exportComposition(config: ExportCompositionConfig): Promise<string>;

    /**
     * Compose a video using the new OpenGL engine
     * @param config Configuration for the composite video
     * @returns Promise resolving to the output video file path
     */
    composeCompositeVideo(config: CompositionConfig): Promise<string>;

    /**
     * Check if the native module is properly loaded
     * @returns true if module is available, false otherwise
     */
    isAvailable(): boolean;

    /**
     * Stitch multiple video files together
     * @param videoPaths Array of paths to input video files
     * @param outputUri Path for the output video file
     * @returns Promise resolving to the output video file path
     */
    stitchVideos(videoPaths: string[], outputUri: string): Promise<string>;

    /**
     * Compress a video file — reduce resolution, bitrate, or re-encode with H.265.
     * Replaces react-native-compressor for post-render compression.
     * Audio is re-encoded on iOS (AAC 128k); copied without re-encode on Android.
     * @param config Compression parameters
     * @returns Promise resolving to the output file URI
     */
    compressVideo(config: CompressVideoConfig): Promise<string>;
}

declare const MediaEngine: MediaEngineInterface;
export default MediaEngine;
