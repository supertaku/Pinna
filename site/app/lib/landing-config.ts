export type QualityTier = "high" | "medium" | "low";

export interface SceneBeat {
  id: string;
  label: string;
  eyebrow: string;
  title: string;
  body: string;
  tags: string[];
  scrollWeight: number;
  camera: {
    position: [number, number, number];
    target: [number, number, number];
    fov: number;
    roll?: number;
  };
  accent: string;
  poster: string;
  interactionIds: string[];
}

export interface LandingConfig {
  brandName: "Pinna";
  downloadUrl: string;
  repositoryUrl: string;
  beats: SceneBeat[];
  audioDefault: true;
  audioUrl: string;
  audioTitle: string;
}

export const landingConfig: LandingConfig = {
  brandName: "Pinna",
  downloadUrl: "https://github.com/supertaku/Pinna/releases",
  repositoryUrl: "https://github.com/supertaku/Pinna",
  audioDefault: true,
  audioUrl: "/audio/clair-de-lune-studio-version.mp3",
  audioTitle: "Clair de Lune (Studio Version)",
  beats: [
    {
      id: "together",
      label: "Together",
      eyebrow: "SAME ROOM",
      title: "One room. One beat.",
      body: "Turn nearby Android phones into one shared listening room. No account required.",
      tags: ["Same Wi‑Fi", "No account", "Android 8+"],
      scrollWeight: 1.4,
      camera: { position: [8.8, 7, 14], target: [0, 0.5, 0], fov: 38 },
      accent: "#5EEAD4",
      poster: "/posters/together.svg",
      interactionIds: ["host-1", "listener-1", "listener-2", "listener-3"],
    },
    {
      id: "choose",
      label: "Choose",
      eyebrow: "YOUR MUSIC",
      title: "Start with what you love.",
      body: "Import tracks from your phone or add a supported audio link.",
      tags: ["Local files", "Audio links"],
      scrollWeight: 1,
      camera: { position: [-8, 6, -3.5], target: [0, 0.4, -17], fov: 39, roll: -0.018 },
      accent: "#14B8A6",
      poster: "/posters/choose.svg",
      interactionIds: ["host-2"],
    },
    {
      id: "create",
      label: "Create",
      eyebrow: "HOST IN SECONDS",
      title: "Create the room.",
      body: "Pinna opens a room over Wi‑Fi, or your phone’s hotspot when you need it.",
      tags: ["Wi‑Fi room", "Hotspot ready"],
      scrollWeight: 1,
      camera: { position: [7.5, 6, -21], target: [0, 0.8, -35], fov: 38, roll: 0.016 },
      accent: "#5EEAD4",
      poster: "/posters/create.svg",
      interactionIds: ["host-3"],
    },
    {
      id: "join",
      label: "Join",
      eyebrow: "SCAN & JOIN",
      title: "Bring everyone in.",
      body: "Friends scan one code and connect on the same local network.",
      tags: ["One scan", "Local network"],
      scrollWeight: 1,
      camera: { position: [-8, 5.6, -38], target: [0, 0.7, -52], fov: 40, roll: -0.018 },
      accent: "#FF8A65",
      poster: "/posters/join.svg",
      interactionIds: ["host-4", "listener-4a", "listener-4b"],
    },
    {
      id: "sync",
      label: "Sync",
      eyebrow: "SYNCED PLAYBACK",
      title: "Hear it land together.",
      body: "Pinna measures delay and corrects drift so playback stays practically in sync.",
      tags: ["Delay measured", "Drift corrected"],
      scrollWeight: 1.4,
      camera: { position: [7.6, 5.2, -55], target: [0, 0.6, -69], fov: 36, roll: 0.014 },
      accent: "#5EEAD4",
      poster: "/posters/sync.svg",
      interactionIds: ["host-5", "listener-5a", "listener-5b", "listener-5c"],
    },
    {
      id: "local",
      label: "Local",
      eyebrow: "LOCAL BY DESIGN",
      title: "The room stays yours.",
      body: "No accounts, no cloud relay, and no analytics. Music moves only across the local room.",
      tags: ["No cloud relay", "No analytics"],
      scrollWeight: 1,
      camera: { position: [-9.2, 11, -72], target: [0, 0, -86], fov: 42, roll: -0.012 },
      accent: "#14B8A6",
      poster: "/posters/local.svg",
      interactionIds: ["room-boundary"],
    },
    {
      id: "download",
      label: "Download",
      eyebrow: "ANDROID 8+",
      title: "Make this room yours.",
      body: "Download Pinna, choose a track, and start listening together.",
      tags: ["Free APK", "Source available"],
      scrollWeight: 1.4,
      camera: { position: [0, 4, -89], target: [0, 0.6, -103], fov: 37 },
      accent: "#5EEAD4",
      poster: "/posters/download.svg",
      interactionIds: ["pinna-mark"],
    },
  ],
};
