/**
 * Tests for MediaEngine behaviour when the native module is unavailable.
 * Requires a separate Jest module scope so we can control requireNativeModule.
 */

jest.mock('expo-modules-core', () => ({
    requireNativeModule: jest.fn(() => {
        throw new Error('Native module not found');
    }),
}));

describe('MediaEngine — native module unavailable', () => {
    let MediaEngine;

    beforeAll(() => {
        MediaEngine = require('../src/index.js').default;
    });

    it('isAvailable returns false', () => {
        expect(MediaEngine.isAvailable()).toBe(false);
    });

    it('getWaveform returns a silence array of the requested length', async () => {
        const result = await MediaEngine.getWaveform('/audio.m4a', 50);
        expect(Array.isArray(result)).toBe(true);
        expect(result).toHaveLength(50);
        expect(result.every(v => v === 0.1)).toBe(true);
    });

    it('extractAudio throws when native is unavailable', async () => {
        await expect(MediaEngine.extractAudio('/v.mp4', '/o.m4a')).rejects.toThrow('MediaEngine unavailable');
    });

    it('exportComposition throws when native is unavailable', async () => {
        await expect(
            MediaEngine.exportComposition({ videoPath: '/v.mp4', outputPath: '/o.mp4' })
        ).rejects.toThrow('MediaEngine unavailable');
    });

    it('composeCompositeVideo throws when native is unavailable', async () => {
        await expect(
            MediaEngine.composeCompositeVideo({ outputUri: '/o.mp4', width: 1280, height: 720, frameRate: 30, tracks: [] })
        ).rejects.toThrow('MediaEngine unavailable');
    });

    it('stitchVideos throws when native is unavailable', async () => {
        await expect(MediaEngine.stitchVideos(['/a.mp4'], '/o.mp4')).rejects.toThrow('MediaEngine unavailable');
    });

    it('compressVideo throws when native is unavailable', async () => {
        await expect(
            MediaEngine.compressVideo({ inputUri: '/in.mp4', outputUri: '/out.mp4' })
        ).rejects.toThrow('MediaEngine unavailable');
    });
});
