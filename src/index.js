
import { requireNativeModule } from 'expo-modules-core';

let MediaEngine = null;
try {
    MediaEngine = requireNativeModule('MediaEngine');
} catch (e) {
    console.warn('MediaEngine native module not found. Rebuild required.');
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
        return await MediaEngine.extractAudio(videoUri, outputUri);
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

        return await MediaEngine.exportComposition({ ...config, bitrate });
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
        return await MediaEngine.composeCompositeVideo(finalConfig);
    },

    isAvailable() {
        return !!MediaEngine;
    }
};
