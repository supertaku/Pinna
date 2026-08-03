import assert from "node:assert/strict";
import test from "node:test";
import { landingConfig } from "../app/lib/landing-config.ts";
import { buildBeatIntervals, clamp01, linger, sampleBeat } from "../app/lib/scroll-math.ts";
import { downgradeQuality, selectQuality } from "../app/lib/quality.ts";

test("weighted intervals cover zero through one in order", () => {
  const intervals = buildBeatIntervals(landingConfig.beats);
  assert.equal(intervals.length, 7);
  assert.equal(intervals[0].start, 0);
  assert.equal(intervals.at(-1).end, 1);
  intervals.forEach((item, index) => {
    assert.ok(item.end > item.start);
    if (index) assert.equal(item.start, intervals[index - 1].end);
  });
});

test("sampling is clamped, deterministic, and reversible", () => {
  const intervals = buildBeatIntervals(landingConfig.beats);
  assert.equal(sampleBeat(-4, intervals).index, 0);
  assert.equal(sampleBeat(4, intervals).index, 6);
  const forward = [0, .12, .38, .62, .91, 1].map((p) => sampleBeat(p, intervals));
  const reverse = [1, .91, .62, .38, .12, 0].map((p) => sampleBeat(p, intervals)).reverse();
  assert.deepEqual(forward, reverse);
  assert.equal(clamp01(-1), 0);
  assert.equal(clamp01(2), 1);
});

test("linger preserves endpoints and finite values", () => {
  assert.equal(linger(0), 0);
  assert.equal(linger(1), 1);
  for (let i = 0; i <= 100; i++) assert.ok(Number.isFinite(linger(i / 100)));
});

test("configuration has complete unique beats and safe links", () => {
  assert.equal(new Set(landingConfig.beats.map((beat) => beat.id)).size, 7);
  landingConfig.beats.forEach((beat) => {
    assert.ok(beat.title && beat.body && beat.poster.startsWith("/posters/"));
    assert.ok(beat.camera.position.every(Number.isFinite));
    assert.ok(beat.camera.target.every(Number.isFinite));
    assert.ok(beat.camera.fov >= 32 && beat.camera.fov <= 42);
  });
  assert.match(landingConfig.downloadUrl, /github\.com\/supertaku\/Pinna\/releases$/);
  assert.equal(landingConfig.audioDefault, true);
  assert.equal(landingConfig.audioUrl, "/audio/clair-de-lune-studio-version.mp3");
});

test("quality selection and downgrade never upgrade", () => {
  assert.equal(selectQuality({ width: 1440, coarse: false, reducedMotion: false, memory: 8, cores: 8, maxTextureSize: 16384 }).tier, "high");
  assert.equal(selectQuality({ width: 390, coarse: true, reducedMotion: false, memory: 4, cores: 4 }).tier, "low");
  assert.equal(downgradeQuality("high").tier, "medium");
  assert.equal(downgradeQuality("medium").tier, "low");
  assert.equal(downgradeQuality("low").tier, "low");
});
