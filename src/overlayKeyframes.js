/**
 * Linearly interpolate a KeyframeValue[] at localTime.
 * Returns defaultValue if keyframes is empty / undefined.
 *
 * @param {Array<{time:number,value:number}>|undefined} keyframes
 * @param {number} localTime
 * @param {number} defaultValue
 * @returns {number}
 */
export function resolveKeyframe(keyframes, localTime, defaultValue) {
    if (!keyframes || keyframes.length === 0) return defaultValue;

    const sorted = [...keyframes].sort((a, b) => a.time - b.time);

    if (localTime <= sorted[0].time) return sorted[0].value;
    if (localTime >= sorted[sorted.length - 1].time) return sorted[sorted.length - 1].value;

    const lo = sorted.findLast((kf) => kf.time <= localTime);
    const hi = sorted.find((kf) => kf.time > localTime);
    if (!lo || !hi) return defaultValue;

    const t = (localTime - lo.time) / (hi.time - lo.time);
    return lo.value + t * (hi.value - lo.value);
}
