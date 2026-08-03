import type { Metadata, Viewport } from "next";
import "@fontsource-variable/manrope";
import "@fontsource-variable/space-grotesk";
import "./globals.css";

const deploymentUrl = process.env.VERCEL_PROJECT_PRODUCTION_URL
  ? `https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}`
  : "https://pinna-one-room.julieannjolo-w.chatgpt.site";

export const metadata: Metadata = {
  metadataBase: new URL(deploymentUrl),
  title: "Pinna: One room. One beat.",
  description: "Create a local listening room and keep nearby Android phones playing together over the same Wi-Fi.",
  icons: { icon: "/favicon.svg", shortcut: "/favicon.svg" },
  openGraph: {
    title: "Pinna: One room. One beat.",
    description: "Nearby Android phones, listening together over the same Wi‑Fi.",
    type: "website",
    images: [{ url: "/og.png", width: 1736, height: 910, alt: "Pinna phones connected in one local listening room" }],
  },
  twitter: { card: "summary_large_image", title: "Pinna: One room. One beat.", description: "Nearby Android phones, listening together over the same Wi‑Fi.", images: ["/og.png"] },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#0B1F1D",
  colorScheme: "dark",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `(() => {
              if ("scrollRestoration" in window.history) {
                window.history.scrollRestoration = "manual";
              }
              const resetScroll = () => {
                window.scrollTo(0, 0);
                window.requestAnimationFrame(() => window.scrollTo(0, 0));
              };
              resetScroll();
              window.addEventListener("pageshow", resetScroll);
            })();`,
          }}
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
