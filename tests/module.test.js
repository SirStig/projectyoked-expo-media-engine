/**
 * Tests for @projectyoked/expo-media-engine JS layer
 *
 * These tests cover the JavaScript bridge logic (quality resolution, error wrapping,
 * API contracts). Native implementations are mocked since they require device hardware.
 */

// ─── Mock Setup ──────────────────────────────────────────────────────────────

const mockNativeModule = {
    extractAudio: jest.fn().mockResolvedValue('/path/to/output.m4a'),
    getWaveform: jest.fn().mockResolvedValue(new Array(100).fill(0.5)),
    exportComposition: jest.fn().mockResolvedValue('/path/to/output.mp4'),
    composeCompositeVideo: jest.fn().mockResolvedValue('/path/to/output.mp4'),
    stitchVideos: jest.fn().mockResolvedValue('/path/to/stitched.mp4'),
    compressVideo: jest.fn().mockResolvedValue('/path/to/compressed.mp4'),
};

jest.mock('expo-modules-core', () => ({
    requireNativeModule: jest.fn(() => mockNativeModule),
}));

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeVideoConfig(overrides = {}) {
    return {
        outputUri: '/output/video.mp4',
        width: 1920,
        height: 1080,
        frameRate: 30,
        tracks: [
            {
                type: 'video',
                clips: [{ uri: 'file:///video.mp4', startTime: 0, duration: 10 }],
            },
        ],
        ...overrides,
    };
}

// ─── Test Suite ───────────────────────────────────────────────────────────────

describe('MediaEngine Module', () => {
    let MediaEngine;

    beforeAll(() => {
        MediaEngine = require('../src/index.js').default;
    });

    beforeEach(() => {
        jest.clearAllMocks();
    });

    // ── Module Structure ────────────────────────────────────────────────────

    describe('Module Structure', () => {
        it('should export default module', () => {
            expect(MediaEngine).toBeDefined();
        });

        it.each([
            'extractAudio', 'getWaveform', 'exportComposition',
            'composeCompositeVideo', 'stitchVideos', 'compressVideo', 'isAvailable',
        ])(
            'should have %s method',
            (method) => {
                expect(typeof MediaEngine[method]).toBe('function');
            }
        );

        it('isAvailable returns true when native module is loaded', () => {
            expect(MediaEngine.isAvailable()).toBe(true);
        });
    });

    // ── extractAudio ────────────────────────────────────────────────────────

    describe('extractAudio', () => {
        it('passes args through and returns string', async () => {
            const result = await MediaEngine.extractAudio('/in/video.mp4', '/out/audio.m4a');
            expect(typeof result).toBe('string');
            expect(mockNativeModule.extractAudio).toHaveBeenCalledWith('/in/video.mp4', '/out/audio.m4a');
        });

        it('wraps native errors with code EXTRACT_AUDIO_FAILED', async () => {
            mockNativeModule.extractAudio.mockRejectedValueOnce(new Error('file not found'));
            await expect(MediaEngine.extractAudio('/bad/path.mp4', '/out.m4a')).rejects.toMatchObject({
                message: 'file not found',
                code: 'EXTRACT_AUDIO_FAILED',
                platform: 'native',
            });
        });
    });

    // ── getWaveform ─────────────────────────────────────────────────────────

    describe('getWaveform', () => {
        it('returns an array with the requested sample count', async () => {
            const result = await MediaEngine.getWaveform('/audio.m4a', 100);
            expect(Array.isArray(result)).toBe(true);
            expect(result).toHaveLength(100);
            expect(mockNativeModule.getWaveform).toHaveBeenCalledWith('/audio.m4a', 100);
        });

        it('defaults samples to 100 when not provided', async () => {
            await MediaEngine.getWaveform('/audio.m4a');
            expect(mockNativeModule.getWaveform).toHaveBeenCalledWith('/audio.m4a', 100);
        });

        it('values are floats between 0 and 1', async () => {
            const result = await MediaEngine.getWaveform('/audio.m4a', 100);
            expect(result.every(v => typeof v === 'number' && v >= 0 && v <= 1)).toBe(true);
        });
    });

    // ── exportComposition ───────────────────────────────────────────────────

    describe('exportComposition', () => {
        it('returns a string', async () => {
            const result = await MediaEngine.exportComposition({
                videoPath: '/video.mp4',
                outputPath: '/output.mp4',
            });
            expect(typeof result).toBe('string');
        });

        it('always passes a numeric bitrate to native', async () => {
            await MediaEngine.exportComposition({ videoPath: '/v.mp4', outputPath: '/o.mp4' });
            const call = mockNativeModule.exportComposition.mock.calls[0][0];
            expect(typeof call.bitrate).toBe('number');
            expect(call.bitrate).toBeGreaterThan(0);
        });

        it.each([
            ['low',    1_000_000],
            ['medium', 4_000_000],
            ['high',  10_000_000],
        ])('resolves quality="%s" to %d bps', async (quality, expected) => {
            await MediaEngine.exportComposition({ videoPath: '/v.mp4', outputPath: '/o.mp4', quality });
            expect(mockNativeModule.exportComposition).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: expected })
            );
        });

        it('uses explicit bitrate over quality string', async () => {
            await MediaEngine.exportComposition({
                videoPath: '/v.mp4', outputPath: '/o.mp4',
                quality: 'low', bitrate: 8_000_000,
            });
            expect(mockNativeModule.exportComposition).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: 8_000_000 })
            );
        });

        it('defaults to 2 Mbps when no quality or bitrate given', async () => {
            await MediaEngine.exportComposition({ videoPath: '/v.mp4', outputPath: '/o.mp4' });
            expect(mockNativeModule.exportComposition).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: 2_000_000 })
            );
        });

        it('wraps native errors with code EXPORT_COMPOSITION_FAILED', async () => {
            mockNativeModule.exportComposition.mockRejectedValueOnce(new Error('codec error'));
            await expect(
                MediaEngine.exportComposition({ videoPath: '/v.mp4', outputPath: '/o.mp4' })
            ).rejects.toMatchObject({
                code: 'EXPORT_COMPOSITION_FAILED',
                platform: 'native',
            });
        });

        it('preserves original config fields unchanged', async () => {
            const config = { videoPath: '/v.mp4', outputPath: '/o.mp4', musicPath: '/music.mp3', musicVolume: 0.5 };
            await MediaEngine.exportComposition(config);
            const call = mockNativeModule.exportComposition.mock.calls[0][0];
            expect(call.musicPath).toBe('/music.mp3');
            expect(call.musicVolume).toBe(0.5);
        });
    });

    // ── composeCompositeVideo ───────────────────────────────────────────────

    describe('composeCompositeVideo', () => {
        it('returns a string', async () => {
            const result = await MediaEngine.composeCompositeVideo(makeVideoConfig());
            expect(typeof result).toBe('string');
        });

        it.each([
            ['low',    1_000_000],
            ['medium', 4_000_000],
            ['high',  10_000_000],
        ])('resolves quality="%s" to %d bps', async (quality, expected) => {
            await MediaEngine.composeCompositeVideo(makeVideoConfig({ quality }));
            expect(mockNativeModule.composeCompositeVideo).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: expected })
            );
        });

        it('uses explicit bitrate over quality string', async () => {
            await MediaEngine.composeCompositeVideo(makeVideoConfig({ quality: 'low', bitrate: 6_000_000 }));
            expect(mockNativeModule.composeCompositeVideo).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: 6_000_000 })
            );
        });

        it('defaults to 4 Mbps when no quality or bitrate given', async () => {
            await MediaEngine.composeCompositeVideo(makeVideoConfig());
            expect(mockNativeModule.composeCompositeVideo).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: 4_000_000 })
            );
        });

        it('passes tracks through to native unmodified', async () => {
            const config = makeVideoConfig({
                tracks: [
                    {
                        type: 'video',
                        clips: [{
                            uri: 'file:///clip.mp4',
                            startTime: 0,
                            duration: 5,
                            clipStart: 2,
                            clipEnd: 7,
                            filter: 'grayscale',
                            filterIntensity: 0.8,
                            opacity: 0.9,
                        }],
                    },
                ],
            });
            await MediaEngine.composeCompositeVideo(config);
            const call = mockNativeModule.composeCompositeVideo.mock.calls[0][0];
            const clip = call.tracks[0].clips[0];
            expect(clip.clipStart).toBe(2);
            expect(clip.clipEnd).toBe(7);
            expect(clip.filter).toBe('grayscale');
            expect(clip.filterIntensity).toBe(0.8);
            expect(clip.opacity).toBe(0.9);
        });

        it('passes volumeEnvelope through to native', async () => {
            const config = makeVideoConfig({
                tracks: [{
                    type: 'audio',
                    clips: [{
                        uri: 'file:///audio.m4a',
                        startTime: 0,
                        duration: 10,
                        volumeEnvelope: { fadeInDuration: 1.5, fadeOutDuration: 2.0 },
                    }],
                }],
            });
            await MediaEngine.composeCompositeVideo(config);
            const call = mockNativeModule.composeCompositeVideo.mock.calls[0][0];
            expect(call.tracks[0].clips[0].volumeEnvelope).toEqual({
                fadeInDuration: 1.5, fadeOutDuration: 2.0,
            });
        });

        it('supports image track type in config', async () => {
            const config = makeVideoConfig({
                tracks: [
                    { type: 'video', clips: [{ uri: 'file:///v.mp4', startTime: 0, duration: 10 }] },
                    { type: 'image', clips: [{ uri: 'file:///overlay.png', startTime: 2, duration: 5 }] },
                ],
            });
            await MediaEngine.composeCompositeVideo(config);
            const call = mockNativeModule.composeCompositeVideo.mock.calls[0][0];
            expect(call.tracks[1].type).toBe('image');
        });

        it('supports text track with textStyle', async () => {
            const config = makeVideoConfig({
                tracks: [{
                    type: 'text',
                    clips: [{
                        uri: 'text:hello',
                        text: 'hello',
                        startTime: 0,
                        duration: 3,
                        textStyle: { color: '#FF0000', fontSize: 48, shadowColor: '#000', shadowRadius: 4 },
                    }],
                }],
            });
            await MediaEngine.composeCompositeVideo(config);
            const call = mockNativeModule.composeCompositeVideo.mock.calls[0][0];
            expect(call.tracks[0].clips[0].textStyle.color).toBe('#FF0000');
        });

        it('wraps native errors with code COMPOSE_VIDEO_FAILED', async () => {
            mockNativeModule.composeCompositeVideo.mockRejectedValueOnce(new Error('encoder failed'));
            await expect(MediaEngine.composeCompositeVideo(makeVideoConfig())).rejects.toMatchObject({
                code: 'COMPOSE_VIDEO_FAILED',
                platform: 'native',
                message: 'encoder failed',
            });
        });
    });

    // ── stitchVideos ────────────────────────────────────────────────────────

    describe('stitchVideos', () => {
        it('passes video paths and output URI to native', async () => {
            const paths = ['/a.mp4', '/b.mp4', '/c.mp4'];
            const result = await MediaEngine.stitchVideos(paths, '/out.mp4');
            expect(typeof result).toBe('string');
            expect(mockNativeModule.stitchVideos).toHaveBeenCalledWith(paths, '/out.mp4');
        });

        it('wraps native errors with code STITCH_VIDEOS_FAILED', async () => {
            mockNativeModule.stitchVideos.mockRejectedValueOnce(new Error('incompatible codecs'));
            await expect(MediaEngine.stitchVideos(['/a.mp4'], '/out.mp4')).rejects.toMatchObject({
                code: 'STITCH_VIDEOS_FAILED',
                platform: 'native',
            });
        });
    });

    // ── Error shape ─────────────────────────────────────────────────────────

    describe('MediaEngineError shape', () => {
        it('errors have code, platform, and message properties', async () => {
            mockNativeModule.extractAudio.mockRejectedValueOnce(new Error('timeout'));
            let caught;
            try {
                await MediaEngine.extractAudio('/bad.mp4', '/out.m4a');
            } catch (e) {
                caught = e;
            }
            expect(caught).toBeDefined();
            expect(caught).toBeInstanceOf(Error);
            expect(typeof caught.code).toBe('string');
            expect(caught.platform).toBe('native');
            expect(typeof caught.message).toBe('string');
        });

        it('error message matches the original native error message', async () => {
            const nativeMsg = 'Failed to load video at /bad.mp4: No such file';
            mockNativeModule.composeCompositeVideo.mockRejectedValueOnce(new Error(nativeMsg));
            await expect(MediaEngine.composeCompositeVideo(makeVideoConfig())).rejects.toMatchObject({
                message: nativeMsg,
            });
        });
    });

    // ── isAvailable ─────────────────────────────────────────────────────────

    describe('isAvailable', () => {
        it('returns true when native module is loaded', () => {
            expect(MediaEngine.isAvailable()).toBe(true);
        });
    });

    // ── compressVideo ────────────────────────────────────────────────────────

    describe('compressVideo', () => {
        it('returns a string (output URI)', async () => {
            const result = await MediaEngine.compressVideo({ inputUri: '/in.mp4', outputUri: '/out.mp4' });
            expect(typeof result).toBe('string');
        });

        it.each([
            ['low',    1_000_000],
            ['medium', 4_000_000],
            ['high',   8_000_000],
        ])('resolves quality="%s" to %d bps', async (quality, expected) => {
            await MediaEngine.compressVideo({ inputUri: '/in.mp4', outputUri: '/out.mp4', quality });
            expect(mockNativeModule.compressVideo).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: expected })
            );
        });

        it('uses explicit bitrate over quality string', async () => {
            await MediaEngine.compressVideo({
                inputUri: '/in.mp4', outputUri: '/out.mp4',
                quality: 'low', bitrate: 5_000_000,
            });
            expect(mockNativeModule.compressVideo).toHaveBeenCalledWith(
                expect.objectContaining({ bitrate: 5_000_000 })
            );
        });

        it('omits bitrate field when neither quality nor bitrate is provided', async () => {
            await MediaEngine.compressVideo({ inputUri: '/in.mp4', outputUri: '/out.mp4' });
            const call = mockNativeModule.compressVideo.mock.calls[0][0];
            expect(call.bitrate).toBeUndefined();
        });

        it('passes maxWidth and maxHeight through to native', async () => {
            await MediaEngine.compressVideo({
                inputUri: '/in.mp4', outputUri: '/out.mp4', maxWidth: 1280, maxHeight: 720,
            });
            expect(mockNativeModule.compressVideo).toHaveBeenCalledWith(
                expect.objectContaining({ maxWidth: 1280, maxHeight: 720 })
            );
        });

        it('passes codec through to native', async () => {
            await MediaEngine.compressVideo({ inputUri: '/in.mp4', outputUri: '/out.mp4', codec: 'h265' });
            expect(mockNativeModule.compressVideo).toHaveBeenCalledWith(
                expect.objectContaining({ codec: 'h265' })
            );
        });

        it('passes audioBitrate and frameRate through to native', async () => {
            await MediaEngine.compressVideo({
                inputUri: '/in.mp4', outputUri: '/out.mp4', audioBitrate: 128000, frameRate: 24,
            });
            expect(mockNativeModule.compressVideo).toHaveBeenCalledWith(
                expect.objectContaining({ audioBitrate: 128000, frameRate: 24 })
            );
        });

        it('wraps native errors with code COMPRESS_VIDEO_FAILED', async () => {
            mockNativeModule.compressVideo.mockRejectedValueOnce(new Error('unsupported codec'));
            await expect(
                MediaEngine.compressVideo({ inputUri: '/in.mp4', outputUri: '/out.mp4' })
            ).rejects.toMatchObject({
                code: 'COMPRESS_VIDEO_FAILED',
                platform: 'native',
                message: 'unsupported codec',
            });
        });
    });
});
