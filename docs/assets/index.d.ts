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

export interface VolumeKeyframe {
    /** Timeline position in seconds */
    time: number;
    /** Volume level 0..1 */
    volume: number;
}

export interface VolumeEnvelope {
    fadeInDuration?: number;
    fadeOutDuration?: number;
    /** Keyframe-based volume automation. Overrides fadeIn/fadeOut when provided. */
    keyframes?: VolumeKeyframe[];
}

export interface TextStyle {
    color?: string;
    fontSize?: number;
    /** 'bold' renders with bold typeface */
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

/** Filter types supported on both platforms */
export type FilterType =
    | 'grayscale'
    | 'sepia'
    | 'vignette'
    | 'invert'
    | 'brightness'
    | 'contrast'
    | 'saturation'
    | 'warm'
    | 'cool';

/** Transition types for clip boundaries */
export type TransitionType =
    | 'crossfade'
    | 'fade'       // fade to black then back
    | 'slide-left'
    | 'slide-right'
    | 'slide-up'
    | 'slide-down'
    | 'zoom-in'
    | 'zoom-out';

export interface KeyframeValue {
    /** Timeline position in seconds, relative to clip startTime */
    time: number;
    value: number;
}

export interface ClipAnimations {
    /** Keyframe-animated x position (0..1) */
    x?: KeyframeValue[];
    /** Keyframe-animated y position (0..1) */
    y?: KeyframeValue[];
    /** Keyframe-animated scale */
    scale?: KeyframeValue[];
    /** Keyframe-animated rotation (degrees) */
    rotation?: KeyframeValue[];
    /** Keyframe-animated opacity (0..1) */
    opacity?: KeyframeValue[];
}

export interface CompositeClip {
    uri: string;
    startTime: number;
    duration: number;
    filter?: FilterType | null;
    filterIntensity?: number; // 0..1
    /** Clip-to-clip transition applied at the END of this clip */
    transition?: TransitionType | null;
    /** How long the transition lasts (seconds). Clips must overlap by this amount. */
    transitionDuration?: number;
    resizeMode?: 'cover' | 'contain' | 'stretch';
    x?: number;
    y?: number;
    scale?: number;
    rotation?: number;
    /** Opacity 0..1 */
    opacity?: number;
    volume?: number;
    // Source trimming
    clipStart?: number;  // Trim start in source (seconds)
    clipEnd?: number;    // Trim end in source (seconds, -1 = full)
    /**
     * Playback speed multiplier.
     * 1.0 = normal, 2.0 = 2x fast forward, 0.5 = half-speed slow motion.
     * Note: values < 1.0 require the source to have been recorded at a higher frame rate
     * for smooth slow motion (e.g. 60fps source for 0.5x at 30fps output).
     */
    speed?: number;
    // Audio fade envelope
    volumeEnvelope?: VolumeEnvelope;
    /** Keyframe-based animation overrides for transform properties */
    animations?: ClipAnimations;
    // Text/image content (for text/image track types)
    text?: string;
    textStyle?: TextStyle;
    // Legacy flat text style fields (still accepted alongside textStyle)
    color?: string;
    fontSize?: number;
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
     * @param outputUri Path for the output audio file (.m4a)
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
     * Export a video composition with text/emoji overlays and audio mixing (legacy API)
     * @param config Configuration object for the composition
     * @returns Promise resolving to the output video file path
     */
    exportComposition(config: ExportCompositionConfig): Promise<string>;

    /**
     * Compose a video using the full multi-track engine with filters, transitions,
     * speed control, opacity, image overlays, and audio mixing.
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
     * Stitch multiple video files together sequentially
     * @param videoPaths Array of paths to input video files (in order)
     * @param outputUri Path for the output video file
     * @returns Promise resolving to the output video file path
     */
    stitchVideos(videoPaths: string[], outputUri: string): Promise<string>;

    /**
     * Compress a video file — reduce resolution, bitrate, or re-encode with H.265.
     * @param config Compression parameters
     * @returns Promise resolving to the output file URI
     */
    compressVideo(config: CompressVideoConfig): Promise<string>;
}

declare const MediaEngine: MediaEngineInterface;
export default MediaEngine;

// ─────────────────────────────────────────────────────────────────────────────
// Preview Engine
// ─────────────────────────────────────────────────────────────────────────────

import type { ComponentType, Ref } from 'react';
import type { StyleProp, ViewStyle } from 'react-native';

/**
 * An overlay clip that is currently visible at the given playback position.
 * Returned by `useCompositionOverlays` for rendering in Skia / RN.
 */
export interface ActiveOverlay {
    /** Unique stable id: "track-{trackIndex}-clip-{clipIndex}" */
    id:         string;
    trackIndex: number;
    clipIndex:  number;
    type:       'text' | 'image';

    // Resolved transforms (same coordinate space as the export)
    x:        number;   // 0..1, normalized horizontal center
    y:        number;   // 0..1, normalized vertical center
    scale:    number;   // multiplier (1.0 = original size)
    rotation: number;   // degrees, clockwise
    opacity:  number;   // 0..1

    // Clip timing
    startTime: number;  // seconds
    duration:  number;  // seconds
    localTime: number;  // currentTime - startTime (seconds)

    // Text
    text?:             string;
    textStyle?:        TextStyle;
    color:             string;
    fontSize:          number;
    fontWeight:        'normal' | 'bold';
    backgroundColor?:  string;
    backgroundPadding: number;
    shadowColor?:      string;
    shadowRadius:      number;
    shadowOffsetX:     number;
    shadowOffsetY:     number;
    strokeColor?:      string;
    strokeWidth:       number;

    // Image
    uri: string;
}

/**
 * Imperative handle returned by MediaEnginePreview's ref.
 */
export interface MediaEnginePreviewRef {
    /** Seek to an absolute position in seconds. */
    seekTo(seconds: number): void;
}

export interface MediaEnginePreviewProps {
    /**
     * The composition to preview. TEXT and IMAGE tracks are intentionally
     * excluded from the native layer — use `useCompositionOverlays` to render
     * them interactively on top via Skia.
     */
    config: CompositionConfig;
    /** Play / pause toggle. Default: false. */
    isPlaying?: boolean;
    /** Mute audio. Default: false. */
    muted?: boolean;
    /**
     * Controlled seek position in seconds. Update this while paused to scrub
     * the timeline (e.g. dragging a thumb on your timeline UI).
     * While playing, the native engine advances time independently.
     */
    currentTime?: number;

    onLoad?:          (event: { nativeEvent: { duration: number } }) => void;
    onTimeUpdate?:    (event: { nativeEvent: { currentTime: number } }) => void;
    onPlaybackEnded?: () => void;
    onError?:         (event: { nativeEvent: { message: string } }) => void;

    style?: StyleProp<ViewStyle>;
}

/**
 * Native preview view. Renders video/audio via the export-accurate pipeline.
 * Wrap with a ref to get access to `seekTo()`.
 *
 * @example
 * ```tsx
 * const previewRef = useRef<MediaEnginePreviewRef>(null);
 * const overlays   = useCompositionOverlays(config, currentTime);
 *
 * <View>
 *   <MediaEnginePreview
 *     ref={previewRef}
 *     config={config}
 *     isPlaying={playing}
 *     onTimeUpdate={e => setCurrentTime(e.nativeEvent.currentTime)}
 *     style={StyleSheet.absoluteFill}
 *   />
 *   {overlays.map(o => (
 *     <InteractiveOverlay key={o.id} overlay={o} />
 *   ))}
 * </View>
 * ```
 */
export declare const MediaEnginePreview: ComponentType<
    MediaEnginePreviewProps & { ref?: Ref<MediaEnginePreviewRef> }
>;

/**
 * Returns the TEXT and IMAGE clips that are active at `currentTime`, with
 * all keyframe-animated transforms resolved.
 *
 * The coordinate space (x/y 0..1, scale, rotation) matches the export engine
 * exactly — pass these values directly to your Skia canvas or RN transforms.
 *
 * @param config      The same CompositionConfig used by MediaEnginePreview.
 * @param currentTime Current playback position in seconds.
 */
export declare function useCompositionOverlays(
    config:      CompositionConfig | null | undefined,
    currentTime: number
): ActiveOverlay[];
