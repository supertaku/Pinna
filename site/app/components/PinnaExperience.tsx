"use client";

import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import Image from "next/image";
import { landingConfig } from "../lib/landing-config";
import { buildBeatIntervals, getDocumentProgress, sampleBeat } from "../lib/scroll-math";

const intervals = buildBeatIntervals(landingConfig.beats);
const AUTO_SCROLL_SPEED = 40;
const AUTO_SCROLL_IDLE_MS = 4200;
const AUTO_SCROLL_START_DELAY_MS = 700;
const AUTO_SCROLL_SCENE_LINGER_MS = 3600;
const SCROLL_KEYS = new Set(["ArrowDown", "ArrowUp", "PageDown", "PageUp", "Home", "End", " "]);
const EYEBROW_BEATS = new Set([0, 4]);
const DETAIL_BEATS = new Set([1, 4, 5]);

class RoomSound {
  private audio: HTMLAudioElement;

  constructor(source: string) {
    this.audio = new Audio(source);
    this.audio.autoplay = false;
    this.audio.loop = true;
    this.audio.preload = "none";
    this.audio.volume = 0.2;
  }

  async enable() {
    try {
      await this.audio.play();
      return true;
    } catch {
      return false;
    }
  }

  disable() {
    this.audio.pause();
  }

  destroy() {
    this.audio.pause();
    this.audio.removeAttribute("src");
    this.audio.load();
  }
}

function PinnaLogo({ compact = false }: { compact?: boolean }) {
  return (
    <span className="brand-lockup" aria-label="Pinna">
      <Image className="brand-mark" src="/brand/pinna-app-icon.svg" alt="" width={40} height={40} priority />
      {!compact && <span>Pinna</span>}
    </span>
  );
}

export function PinnaExperience() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const worldRef = useRef<import("../lib/pinna-world").PinnaWorld | null>(null);
  const soundRef = useRef<RoomSound | null>(null);
  const activeRef = useRef(0);
  const tourHoldingRef = useRef(false);
  const tourTimerRef = useRef<number | null>(null);
  const [active, setActive] = useState(0);
  const [sound, setSound] = useState(false);
  const [autoplayBlocked, setAutoplayBlocked] = useState(false);
  const [introOpen, setIntroOpen] = useState(true);
  const [quality, setQuality] = useState<"high" | "medium" | "low">("medium");
  const [fallback, setFallback] = useState<string | null>(null);
  const [reducedMotion, setReducedMotion] = useState(false);
  const [selection, setSelection] = useState<{ label: string; detail: string } | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const media = matchMedia("(prefers-reduced-motion: reduce)");
    const forceStatic = new URLSearchParams(window.location.search).has("static");
    if (media.matches || forceStatic) {
      requestAnimationFrame(() => {
        setReducedMotion(media.matches);
        setFallback(forceStatic ? "Static mode is active." : "Motion is reduced in your system settings.");
      });
      return;
    }
    if (!canvasRef.current) return;
    let cancelled = false;
    import("../lib/pinna-world").then(({ PinnaWorld }) => {
      if (cancelled || !canvasRef.current) return;
      try {
        worldRef.current = new PinnaWorld(canvasRef.current, {
          onSelect: setSelection,
          onFallback: setFallback,
          onQuality: setQuality,
        });
        setReady(true);
      } catch {
        setFallback("This browser cannot start the real-time 3D renderer.");
      }
    });
    return () => {
      cancelled = true;
      worldRef.current?.dispose();
      worldRef.current = null;
    };
  }, []);

  useEffect(() => {
    let frame = 0;
    const updateWorld = () => {
      const progress = getDocumentProgress(window.scrollY, document.documentElement.scrollHeight, window.innerHeight);
      worldRef.current?.setTargetProgress(progress);
      frame = requestAnimationFrame(updateWorld);
    };
    frame = requestAnimationFrame(updateWorld);
    return () => cancelAnimationFrame(frame);
  }, []);

  useEffect(() => {
    const sections = Array.from(document.querySelectorAll<HTMLElement>(".story-beat"));
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.find((entry) => entry.isIntersecting);
      if (!visible) return;
      const index = Number((visible.target as HTMLElement).dataset.index);
      if (!Number.isFinite(index) || index === activeRef.current) return;
      activeRef.current = index;
      setActive(index);
    }, { rootMargin: "-48% 0px -48% 0px", threshold: 0 });
    sections.forEach((section) => observer.observe(section));
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (introOpen || reducedMotion) return;

    let frame = 0;
    let lastFrame = performance.now();
    let autoPosition = window.scrollY;
    let resumeAt = lastFrame + AUTO_SCROLL_START_DELAY_MS;
    let lingeredBeat = -1;

    const currentSample = () => {
      const progress = getDocumentProgress(window.scrollY, document.documentElement.scrollHeight, window.innerHeight);
      return sampleBeat(progress, intervals);
    };

    const holdTour = (duration: number) => {
      resumeAt = performance.now() + duration;
      if (tourTimerRef.current !== null) window.clearTimeout(tourTimerRef.current);
      if (!tourHoldingRef.current) {
        tourHoldingRef.current = true;
      }
      tourTimerRef.current = window.setTimeout(() => {
        tourHoldingRef.current = false;
        tourTimerRef.current = null;
      }, duration);
    };

    const pauseForUser = () => {
      autoPosition = window.scrollY;
      lingeredBeat = currentSample().index;
      holdTour(AUTO_SCROLL_IDLE_MS);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (SCROLL_KEYS.has(event.key)) pauseForUser();
    };
    const onPointerMove = (event: PointerEvent) => {
      if (event.buttons) pauseForUser();
    };
    const tick = (now: number) => {
      const elapsed = Math.min(now - lastFrame, 50);
      lastFrame = now;

      const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      if (!document.hidden && now >= resumeAt && window.scrollY < maxScroll) {
        const sample = currentSample();
        if (sample.local >= 0.2 && sample.index !== lingeredBeat) {
          lingeredBeat = sample.index;
          holdTour(AUTO_SCROLL_SCENE_LINGER_MS);
          frame = requestAnimationFrame(tick);
          return;
        }
        autoPosition = Math.max(autoPosition, window.scrollY);
        autoPosition = Math.min(maxScroll, autoPosition + (AUTO_SCROLL_SPEED * elapsed) / 1000);
        window.scrollTo({ top: autoPosition, left: 0, behavior: "auto" });
      } else {
        autoPosition = window.scrollY;
      }

      frame = requestAnimationFrame(tick);
    };

    window.addEventListener("wheel", pauseForUser, { passive: true });
    window.addEventListener("touchstart", pauseForUser, { passive: true });
    window.addEventListener("touchmove", pauseForUser, { passive: true });
    window.addEventListener("pointerdown", pauseForUser, { passive: true });
    window.addEventListener("pointermove", onPointerMove, { passive: true });
    window.addEventListener("pointerup", pauseForUser, { passive: true });
    window.addEventListener("keydown", onKeyDown);
    frame = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(frame);
      if (tourTimerRef.current !== null) {
        window.clearTimeout(tourTimerRef.current);
        tourTimerRef.current = null;
      }
      window.removeEventListener("wheel", pauseForUser);
      window.removeEventListener("touchstart", pauseForUser);
      window.removeEventListener("touchmove", pauseForUser);
      window.removeEventListener("pointerdown", pauseForUser);
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", pauseForUser);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [introOpen, reducedMotion]);

  useEffect(() => {
    const playback = new RoomSound(landingConfig.audioUrl);
    soundRef.current = playback;
    return () => {
      playback.destroy();
      soundRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!introOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [introOpen]);

  useEffect(() => {
    const onVisibility = () => {
      if (!soundRef.current) return;
      if (document.hidden) soundRef.current.disable();
      else if (sound && !autoplayBlocked && !introOpen) {
        void soundRef.current.enable().then((started) => {
          if (!started) setAutoplayBlocked(true);
        });
      }
    };
    document.addEventListener("visibilitychange", onVisibility);
    return () => document.removeEventListener("visibilitychange", onVisibility);
  }, [sound, autoplayBlocked, introOpen]);

  const explore = useCallback(async () => {
    worldRef.current?.setPreviewing(false);
    const started = await soundRef.current?.enable();
    setAutoplayBlocked(!started);
    setSound(Boolean(started));
    setIntroOpen(false);
  }, []);

  const toggleSound = useCallback(async () => {
    if (sound && !autoplayBlocked) {
      soundRef.current?.disable();
      setSound(false);
      return;
    }
    const started = await soundRef.current?.enable();
    setAutoplayBlocked(!started);
    setSound(Boolean(started));
  }, [sound, autoplayBlocked]);

  const jumpTo = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth", block: "start" });
  };

  const staticMode = reducedMotion || Boolean(fallback);

  return (
    <>
      {introOpen && (
        <div className="intro-modal" role="dialog" aria-modal="true" aria-labelledby="intro-title" aria-describedby="intro-description">
          <div className="intro-backdrop" aria-hidden="true" />
          <div className="intro-card">
            <div className="intro-illustration" aria-hidden="true">
              <Image className="intro-platform-preview" src={landingConfig.beats[0].poster} alt="" fill priority sizes="(max-width: 720px) 100vw, 30vw" />
              <span className="intro-ripple intro-ripple-one" />
              <span className="intro-ripple intro-ripple-two" />
              <span className="intro-ripple intro-ripple-three" />
              <span className="intro-ear"><i /></span>
              <b>pinna</b>
            </div>
            <div className="intro-copy">
              <p className="intro-eyebrow">EAR TRIVIA</p>
              <h2 id="intro-title">A small part with a big job.</h2>
              <p id="intro-description">The <strong>pinna</strong> is the visible outer part of your ear. Its curves collect sound waves and guide them into the ear canal, while also helping you tell where a sound is coming from.</p>
              <button className="button intro-explore" type="button" autoFocus onClick={() => void explore()}>
                Explore <span aria-hidden="true">&rarr;</span>
              </button>
            </div>
          </div>
        </div>
      )}
      <a className="skip-link" href="#story">Skip to the story</a>
      <header className="site-header">
        <a className="brand-link" href="#together" onClick={(event) => { event.preventDefault(); jumpTo("together"); }}>
          <PinnaLogo />
        </a>
        <div className="header-actions">
          <button className="sound-toggle" type="button" aria-pressed={sound && !autoplayBlocked} onClick={() => void toggleSound()} title={sound && !autoplayBlocked ? `Pause ${landingConfig.audioTitle}` : `Play ${landingConfig.audioTitle}`}>
            <span className="sound-bars" aria-hidden="true"><i /><i /><i /></span>
            <span>{autoplayBlocked ? "Play music" : sound ? "Clair de Lune" : "Music off"}</span>
          </button>
          <a className="button button-small" href={landingConfig.downloadUrl}>Download Pinna</a>
        </div>
      </header>

      <div className={`world-shell ${ready ? "is-ready" : ""} ${introOpen ? "is-previewing" : ""} ${staticMode ? "is-static" : ""}`} aria-hidden="true">
        <canvas ref={canvasRef} className="world-canvas" />
        <div className="world-glow" />
        {!ready && !staticMode && <div className="loading-orbit"><span /><b>Building the room</b></div>}
      </div>

      <aside className="route-rail" aria-label="Journey progress">
        <span className="route-line" aria-hidden="true"><i style={{ transform: `scaleY(${(active + 1) / landingConfig.beats.length})` }} /></span>
        {landingConfig.beats.map((beat, index) => (
          <button key={beat.id} type="button" className={active === index ? "is-active" : ""} onClick={() => jumpTo(beat.id)} aria-label={`Go to ${beat.label}`}>
            <span aria-hidden="true" /><b>{beat.label}</b>
          </button>
        ))}
      </aside>

      <div className="mobile-progress" aria-hidden="true"><i style={{ width: `${((active + 1) / landingConfig.beats.length) * 100}%` }} /></div>

      {selection && !staticMode && <div className="world-label" role="status"><b>{selection.label}</b><span>{selection.detail}</span></div>}
      <div className="quality-chip" role="status" aria-live="polite" title="3D rendering quality">
        <span className={ready ? "live-dot" : ""} />
        {staticMode ? "Static story" : `${quality[0].toUpperCase()}${quality.slice(1)} 3D`}
      </div>

      <main id="story">
        {landingConfig.beats.map((beat, index) => {
          const style = { "--beat-vh": `${Math.round(beat.scrollWeight * 112)}vh` } as CSSProperties;
          return (
            <section id={beat.id} key={beat.id} className={`story-beat beat-${index + 1} story-layout-${index + 1}`} style={style} data-index={index} data-active={active === index}>
              {index === 0 && !staticMode && (
                <div className="hero-poster-live" aria-hidden="true">
                  <Image src={beat.poster} alt="" fill priority sizes="(max-width: 720px) 100vw, 48vw" />
                </div>
              )}
              {staticMode && (
                <div className="static-poster" style={{ "--poster-accent": beat.accent } as CSSProperties}>
                  <Image src={beat.poster} alt="" fill sizes="(max-width: 720px) 100vw, 48vw" priority={index < 2} />
                </div>
              )}
              <div className="story-copy">
                {EYEBROW_BEATS.has(index) && <div className="beat-kicker">{beat.eyebrow}</div>}
                <h1>{beat.title}</h1>
                <p>{beat.body}</p>
                {DETAIL_BEATS.has(index) && <ul className="beat-facts" aria-label="Highlights">{beat.tags.map((tag) => <li key={tag}>{tag}</li>)}</ul>}
                {index === 0 && (
                  <div className="hero-actions">
                    <a className="button" href={landingConfig.downloadUrl}>Download Pinna</a>
                  </div>
                )}
                {index === 5 && <p className="ear-note"><strong>Why Pinna?</strong> The pinna is the outer ear. Its curves collect sound and help locate where it came from.</p>}
                {index === 6 && (
                  <div className="final-actions">
                    <a className="button" href={landingConfig.downloadUrl}>Download Pinna</a>
                    <a className="text-link" href={landingConfig.repositoryUrl}>View source on GitHub</a>
                  </div>
                )}
              </div>
            </section>
          );
        })}
      </main>

      <footer>
        <PinnaLogo compact />
        <p>Made for the phones already in the room.</p>
        <div><a href={landingConfig.repositoryUrl}>Source</a><a href={landingConfig.downloadUrl}>Releases</a></div>
        <small>Local-first / Tracker-free / Music starts only when you choose</small>
      </footer>
    </>
  );
}
