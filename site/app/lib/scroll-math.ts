import type { SceneBeat } from "./landing-config";

export interface BeatInterval {
  id: string;
  index: number;
  start: number;
  end: number;
}

export const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

export function buildBeatIntervals(beats: Pick<SceneBeat, "id" | "scrollWeight">[]): BeatInterval[] {
  const total = beats.reduce((sum, beat) => sum + beat.scrollWeight, 0);
  let cursor = 0;
  return beats.map((beat, index) => {
    const start = cursor / total;
    cursor += beat.scrollWeight;
    return { id: beat.id, index, start, end: cursor / total };
  });
}

export function sampleBeat(progress: number, intervals: BeatInterval[]) {
  const p = clamp01(progress);
  const interval = intervals.find((item) => p <= item.end) ?? intervals.at(-1)!;
  const span = Math.max(0.0001, interval.end - interval.start);
  return { ...interval, local: clamp01((p - interval.start) / span) };
}

export function linger(value: number) {
  const t = clamp01(value);
  return t * t * (3 - 2 * t);
}

export function damp(current: number, target: number, smoothing: number, delta: number) {
  return current + (target - current) * (1 - Math.exp(-smoothing * delta));
}

export function getDocumentProgress(scrollY: number, scrollHeight: number, viewportHeight: number) {
  return clamp01(scrollY / Math.max(1, scrollHeight - viewportHeight));
}

