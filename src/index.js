
import { requireNativeModule } from 'expo-modules-core';

let MediaEngine = null;
try {
    MediaEngine = requireNativeModule('MediaEngine');
} catch (e) {
    console.warn('MediaEngine native module not found. Rebuild required.');
}

function makeMediaEngineError(e, code) {
    const err = new Error(e?.message ?? String(e));
    err.code = code;
    err.platform = 'native';
    return err;
}

/**
 * Native Media Engine
 */
export default {
    /**
     * Extract audio from video
     */
    async extractAudio(videoUri, outputUri) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");
        try {
            return await MediaEngine.extractAudio(videoUri, outputUri);
        } catch (e) {
            throw makeMediaEngineError(e, 'EXTRACT_AUDIO_FAILED');
        }
    },

    /**
     * Get Waveform Amplitude Data
     */
    async getWaveform(audioUri, samples = 100) {
        if (!MediaEngine) return new Array(samples).fill(0.1);
        return await MediaEngine.getWaveform(audioUri, samples);
    },

    /**
     * Export Video Composition
     */
    async exportComposition(config) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");

        // Resolve Quality/Bitrate
        let bitrate = config.bitrate;
        if (!bitrate && config.quality) {
            switch (config.quality) {
                case 'low': bitrate = 1000000; break;
                case 'medium': bitrate = 4000000; break;
                case 'high': bitrate = 10000000; break;
            }
        }
        if (!bitrate) bitrate = 2000000; // Legacy Default 2Mbps

        try {
            return await MediaEngine.exportComposition({ ...config, bitrate });
        } catch (e) {
            throw makeMediaEngineError(e, 'EXPORT_COMPOSITION_FAILED');
        }
    },

    /**
     * Compose specific video with multiple tracks/clips
     */
    async composeCompositeVideo(config) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");

        // Resolve Quality/Bitrate
        let bitrate = config.bitrate;
        if (!bitrate && config.quality) {
            switch (config.quality) {
                case 'low': bitrate = 1000000; break; // 1 Mbps
                case 'medium': bitrate = 4000000; break; // 4 Mbps
                case 'high': bitrate = 10000000; break; // 10 Mbps
            }
        }
        if (!bitrate) bitrate = 4000000; // Default to Medium (4Mbps)

        const finalConfig = { ...config, bitrate };
        try {
            return await MediaEngine.composeCompositeVideo(finalConfig);
        } catch (e) {
            throw makeMediaEngineError(e, 'COMPOSE_VIDEO_FAILED');
        }
    },

    isAvailable() {
        return !!MediaEngine;
    },

    /**
     * Stitch multiple videos together
     */
    async stitchVideos(videoPaths, outputUri) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");
        try {
            return await MediaEngine.stitchVideos(videoPaths, outputUri);
        } catch (e) {
            throw makeMediaEngineError(e, 'STITCH_VIDEOS_FAILED');
        }
    },

    /**
     * Compress a video (reduce file size / resolution / bitrate).
     * Replaces react-native-compressor for post-render use.
     */
    async compressVideo(config) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");

        // Resolve quality → bitrate if explicit bitrate not set
        let bitrate = config.bitrate;
        if (!bitrate && config.quality) {
            switch (config.quality) {
                case 'low':  bitrate = 1_000_000; break;
                case 'medium': bitrate = 4_000_000; break;
                case 'high': bitrate = 8_000_000; break;
            }
        }

        const finalConfig = { ...config, ...(bitrate ? { bitrate } : {}) };
        try {
            return await MediaEngine.compressVideo(finalConfig);
        } catch (e) {
            throw makeMediaEngineError(e, 'COMPRESS_VIDEO_FAILED');
        }
    },
};
