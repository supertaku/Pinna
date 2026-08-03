import type { QualityTier } from "./landing-config";

export interface QualityProfile {
  tier: QualityTier;
  dpr: number;
  particles: number;
  shadows: boolean;
  post: "full" | "medium" | "none";
  segments: number;
}

export const QUALITY: Record<QualityTier, QualityProfile> = {
  high: { tier: "high", dpr: 1.75, particles: 160, shadows: true, post: "full", segments: 24 },
  medium: { tier: "medium", dpr: 1.5, particles: 96, shadows: true, post: "medium", segments: 16 },
  low: { tier: "low", dpr: 1.25, particles: 48, shadows: false, post: "none", segments: 10 },
};

export function selectQuality(input: {
  width: number;
  coarse: boolean;
  reducedMotion: boolean;
  memory?: number;
  cores?: number;
  maxTextureSize?: number;
}): QualityProfile {
  if (input.reducedMotion || input.width < 420 || (input.memory ?? 8) <= 2 || (input.cores ?? 8) <= 2) return QUALITY.low;
  if (input.coarse || input.width < 980 || (input.memory ?? 8) <= 4 || (input.maxTextureSize ?? 8192) < 8192) return QUALITY.medium;
  return QUALITY.high;
}

export function downgradeQuality(tier: QualityTier): QualityProfile {
  return tier === "high" ? QUALITY.medium : QUALITY.low;
}
