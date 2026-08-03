"use client";

import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import Image from "next/image";
import { landingConfig } from "../lib/landing-config";
import { buildBeatIntervals, getDocumentProgress, sampleBeat } from "../lib/scroll-math";

const intervals = buildBeatIntervals(landingConfig.beats);

class RoomSound {
  private audio: HTMLAudioElement;

  constructor(source: string) {
    this.audio = new Audio(source);
    this.audio.autoplay = false;
    this.audio.loop = true;
    this.audio.preload = "auto";
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
      <svg className="brand-mark" viewBox="0 0 108 108" aria-hidden="true">
        <rect width="108" height="108" rx="28" fill="#0F766E" />
        <path fill="#F4F7F2" d="M28,28h24c15,0 28,12 28,27s-13,27-28,27h-8V64h8c5,0 10-4 10-9s-5-9-10-9H46v42H28z" />
      </svg>
      {!compact && <span>Pinna</span>}
    </span>
  );
}

export function PinnaExperience() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const worldRef = useRef<import("../lib/pinna-world").PinnaWorld | null>(null);
  const soundRef = useRef<RoomSound | null>(null);
  const activeRef = useRef(0);
  const [active, setActive] = useState(0);
  const [sound, setSound] = useState(false);
  const [autoplayBlocked, setAutoplayBlocked] = useState(false);
  const [introOpen, setIntroOpen] = useState(true);
  const [quality, setQuality] = useState<"high" | "medium" | "low" | "static">("static");
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
    let queued = false;
    const update = () => {
      queued = false;
      const progress = getDocumentProgress(window.scrollY, document.documentElement.scrollHeight, window.innerHeight);
      worldRef.current?.setTargetProgress(progress);
      const index = sampleBeat(progress, intervals).index;
      if (index !== activeRef.current) {
        activeRef.current = index;
        setActive(index);
      }
    };
    const onScroll = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(update);
    };
    update();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

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
        <nav className="header-rail" aria-label="Story sections">
          {landingConfig.beats.map((beat, index) => (
            <button key={beat.id} type="button" className={active === index ? "is-active" : ""} onClick={() => jumpTo(beat.id)} aria-current={active === index ? "step" : undefined}>
              <span>{String(index + 1).padStart(2, "0")}</span>{beat.label}
            </button>
          ))}
        </nav>
        <div className="header-actions">
          <button className="sound-toggle" type="button" aria-pressed={sound && !autoplayBlocked} onClick={() => void toggleSound()} title={sound && !autoplayBlocked ? `Pause ${landingConfig.audioTitle}` : `Play ${landingConfig.audioTitle}`}>
            <span className="sound-bars" aria-hidden="true"><i /><i /><i /></span>
            <span>{autoplayBlocked ? "Play music" : sound ? "Clair de Lune" : "Music off"}</span>
          </button>
          <a className="button button-small" href={landingConfig.downloadUrl}>Download</a>
        </div>
      </header>

      <div className={`world-shell ${ready ? "is-ready" : ""} ${staticMode ? "is-static" : ""}`} aria-hidden="true">
        <canvas ref={canvasRef} className="world-canvas" />
        <div className="world-glow" />
        {!ready && !staticMode && <div className="loading-orbit"><span /><b>Building the room</b></div>}
      </div>

      <aside className="route-rail" aria-label="Journey progress">
        <span className="route-line" aria-hidden="true"><i style={{ transform: `scaleY(${(active + 1) / landingConfig.beats.length})` }} /></span>
        {landingConfig.beats.map((beat, index) => (
          <button key={beat.id} type="button" className={active === index ? "is-active" : ""} onClick={() => jumpTo(beat.id)} aria-label={`Go to ${beat.label}`}>
            <span>{String(index + 1).padStart(2, "0")}</span><b>{beat.label}</b>
          </button>
        ))}
      </aside>

      <div className="mobile-progress" aria-hidden="true"><i style={{ width: `${((active + 1) / landingConfig.beats.length) * 100}%` }} /></div>

      {selection && !staticMode && <div className="world-label" role="status"><b>{selection.label}</b><span>{selection.detail}</span></div>}
      <div className="quality-chip" aria-hidden="true"><span className={ready ? "live-dot" : ""} />{staticMode ? "Static story" : `${quality} 3D`}</div>

      <main id="story">
        {landingConfig.beats.map((beat, index) => {
          const style = { "--beat-vh": `${Math.round(beat.scrollWeight * 112)}vh` } as CSSProperties;
          return (
            <section id={beat.id} key={beat.id} className={`story-beat beat-${index + 1}`} style={style} data-active={active === index}>
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
              <div className="copy-card">
                <div className="beat-kicker"><span>{String(index + 1).padStart(2, "0")}</span>{beat.eyebrow}</div>
                <h1>{beat.title}</h1>
                <p>{beat.body}</p>
                <ul aria-label="Highlights">{beat.tags.map((tag) => <li key={tag}>{tag}</li>)}</ul>
                {index === 0 && (
                  <div className="hero-actions">
                    <a className="button" href={landingConfig.downloadUrl}>Get Pinna <span aria-hidden="true">↗</span></a>
                    <span>Android 8+ · APK via GitHub Releases</span>
                  </div>
                )}
                {index === 6 && (
                  <div className="final-actions">
                    <a className="button" href={landingConfig.downloadUrl}>Download Pinna <span aria-hidden="true">↗</span></a>
                    <a className="text-link" href={landingConfig.repositoryUrl}>View source on GitHub <span aria-hidden="true">↗</span></a>
                    <small>Android 8+ · APK via GitHub Releases</small>
                  </div>
                )}
              </div>
              {index === 0 && <button className="scroll-cue" type="button" onClick={() => jumpTo("choose")}><span aria-hidden="true" />Scroll to enter the room</button>}
            </section>
          );
        })}
      </main>

      <footer>
        <PinnaLogo compact />
        <p>Made for the phones already in the room.</p>
        <div><a href={landingConfig.repositoryUrl}>Source</a><a href={landingConfig.downloadUrl}>Releases</a></div>
        <small>Local-first · Tracker-free · Clair de Lune begins when you explore</small>
      </footer>
    </>
  );
}
