# Pinna landing page

A real-time Three.js scroll story for [Pinna](https://github.com/supertaku/Pinna), the local Android listening room.

## Commands

```bash
npm install
npm run dev
npm run lint
npm run test:all
npm run build:vercel
```

The page is a Sites-compatible Vinext app with Cloudflare Worker output. The semantic story renders before the lazy Three.js world, and `?static=1` exposes the same poster fallback used when motion is reduced or WebGL is unavailable.

No analytics, cookies, remote fonts, accounts, or cloud APIs are used by the page. The locally bundled Clair de Lune track starts from the opening Explore action, ensuring reliable playback from an explicit user gesture.

## Vercel

When importing the parent `supertaku/Pinna` repository in Vercel, set the project Root Directory to `site`. The included `vercel.json` selects Next.js and runs the dedicated `npm run build:vercel` command. Keep the Output Directory on the framework default.
