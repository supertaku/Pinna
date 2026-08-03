import assert from "node:assert/strict";
import test from "node:test";

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  return worker.fetch(new Request("http://localhost/", { headers: { accept: "text/html" } }), { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } }, { waitUntil() {}, passThroughOnException() {} });
}

test("server-renders Pinna metadata and semantic story", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);
  const html = await response.text();
  assert.match(html, /Pinna: One room\. One beat\./);
  assert.match(html, /Create a local listening room/);
  assert.match(html, /No account required/);
  assert.match(html, /Download Pinna/);
  assert.match(html, /Clair de Lune/);
  assert.match(html, /EAR TRIVIA/);
  assert.match(html, /A small part with a big job/);
  assert.match(html, />Explore\s/);
  assert.match(html, /Why Pinna\?/);
  assert.match(html, /quality-chip/);
  assert.match(html, /Medium 3D/);
  assert.match(html, /is-previewing/);
  assert.doesNotMatch(html, /Pause tour|Enter with sound|Continue muted|Scroll to enter the room/);
  assert.match(html, /hero-poster-live/);
  assert.match(html, /\/posters\/together\.svg/);
  assert.match(html, /scrollRestoration/);
  assert.match(html, /pageshow/);
  assert.doesNotMatch(html, /[—–]/);
  assert.doesNotMatch(html, /codex-preview|react-loading-skeleton|google-analytics|googletagmanager/i);
});
