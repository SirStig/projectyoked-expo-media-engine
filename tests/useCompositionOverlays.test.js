import { resolveKeyframe } from '../src/overlayKeyframes.js';

describe('resolveKeyframe', () => {
    it('returns default when keyframes empty or undefined', () => {
        expect(resolveKeyframe(undefined, 0.5, 7)).toBe(7);
        expect(resolveKeyframe([], 0.5, 7)).toBe(7);
    });

    it('returns first value at or before start', () => {
        const kf = [{ time: 1, value: 10 }, { time: 3, value: 20 }];
        expect(resolveKeyframe(kf, 0, 0)).toBe(10);
        expect(resolveKeyframe(kf, 1, 0)).toBe(10);
    });

    it('returns last value at or after end', () => {
        const kf = [{ time: 0, value: 0 }, { time: 2, value: 100 }];
        expect(resolveKeyframe(kf, 2, -1)).toBe(100);
        expect(resolveKeyframe(kf, 5, -1)).toBe(100);
    });

    it('interpolates between two keyframes', () => {
        const kf = [{ time: 0, value: 0 }, { time: 2, value: 10 }];
        expect(resolveKeyframe(kf, 1, -1)).toBeCloseTo(5);
    });

    it('sorts unsorted keyframes', () => {
        const kf = [{ time: 2, value: 10 }, { time: 0, value: 0 }];
        expect(resolveKeyframe(kf, 1, -1)).toBeCloseTo(5);
    });

    it('handles three keyframes mid segment', () => {
        const kf = [
            { time: 0, value: 0 },
            { time: 1, value: 10 },
            { time: 3, value: 30 },
        ];
        expect(resolveKeyframe(kf, 0.5, -1)).toBeCloseTo(5);
        expect(resolveKeyframe(kf, 2, -1)).toBeCloseTo(20);
    });
});
