/**
 * Type definitions for @projectyoked/react-native-media-engine
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

export interface CompositeClip {
    uri: string;
    startTime: number;
    duration: number;
    filter?: 'grayscale' | 'sepia' | null;
    transition?: string | null;
}

export interface CompositeTrack {
    type: 'video' | 'audio' | 'text';
    clips: CompositeClip[];
}

export interface CompositionConfig {
    outputUri: string;
    width: number;
    height: number;
    frameRate: number;
    frameRate: number;
    bitrate?: number;
    videoBitrate?: number;
    audioBitrate?: number;
    enablePassthrough?: boolean;
    videoProfile?: 'baseline' | 'main' | 'high';
    quality?: 'low' | 'medium' | 'high';
    tracks: CompositeTrack[];
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
}

declare const MediaEngine: MediaEngineInterface;
export default MediaEngine;
