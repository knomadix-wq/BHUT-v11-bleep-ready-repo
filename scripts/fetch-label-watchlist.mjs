import { chromium } from "playwright";
import { mkdir, readFile, writeFile } from "node:fs/promises";

const labels = [
  { name: "SHELTER PRESS", url: "https://shelterpress.bandcamp.com/music" },
  { name: "LATENCY", url: "https://latencyrecordings.bandcamp.com/music" },
  { name: "EDITIONS MEGO", url: "https://editionsmego.bandcamp.com/music" },
  { name: "RASTER", url: "https://raster-raster.bandcamp.com/music" },
];
const normalise = (value) => value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
const existing = JSON.parse(await readFile("data/label-watchlist.json", "utf8").catch(() => '{"releases":[]}'));
const existingReleases = Array.isArray(existing.releases) ? existing.releases : [];
const knownUrls = new Set(existingReleases.map((release) => release.url).filter(Boolean));

async function spotifyArtwork(spotifyId, expectedTitle) {
  const response = await fetch(
    `https://open.spotify.com/oembed?url=${encodeURIComponent(`https://open.spotify.com/album/${spotifyId}`)}`,
  );
  if (!response.ok) throw new Error(`Spotify artwork HTTP ${response.status}`);
  const metadata = await response.json();
  const actual = normalise(metadata.title || "");
  const expected = normalise(expectedTitle);
  if (!actual || (actual !== expected && !actual.includes(expected) && !expected.includes(actual))) {
    throw new Error(`Spotify returned the wrong album: ${metadata.title || "unknown"}`);
  }
  if (!metadata.thumbnail_url) throw new Error("Spotify returned no artwork");
  return metadata.thumbnail_url;
}

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({
    locale: "en-GB",
    timezoneId: "Europe/Paris",
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
  });
  const cataloguePage = await context.newPage();
  const candidates = [];
  for (const label of labels) {
    await cataloguePage.goto(label.url, { waitUntil: "domcontentloaded", timeout: 90_000 });
    const releases = await cataloguePage.locator(".music-grid-item").evaluateAll((items) =>
      items.slice(0, 1).map((item) => ({
        title: item.querySelector(".title")?.textContent?.trim() || "",
        artist: item.querySelector(".artist-override")?.textContent?.replace(/^by\s+/i, "").trim() || "",
        url: item.querySelector('a[href*="/album/"]')?.href || "",
      })).filter((item) => item.title && item.artist && item.url),
    );
    candidates.push(...releases
      .filter((release) => !knownUrls.has(release.url))
      .map((release) => ({ ...release, source: label.name })));
  }

  const unique = [...new Map(candidates.map((release) => [
    `${normalise(release.artist)}|${normalise(release.title)}|${release.source}`, release,
  ])).values()];
  const spotifyPage = await context.newPage();
  const resolved = [];
  for (const release of unique) {
    try {
      const query = encodeURIComponent(`${release.artist} ${release.title}`);
      await spotifyPage.goto(`https://open.spotify.com/search/${query}/albums`, {
        waitUntil: "domcontentloaded", timeout: 30_000,
      });
      const albumLink = spotifyPage.locator('a[href*="/album/"]').first();
      await albumLink.waitFor({ state: "attached", timeout: 15_000 });
      const spotifyId = (await albumLink.getAttribute("href"))?.match(/\/album\/([^/?]+)/)?.[1];
      if (!spotifyId) throw new Error("Spotify returned no album ID");
      const cover = await spotifyArtwork(spotifyId, release.title);
      resolved.push({ ...release, spotifyId, cover });
    } catch (error) {
      console.warn(`Skipped ${release.source}: ${release.artist} — ${release.title}: ${error.message}`);
    }
  }
  const combined = [...new Map([...existingReleases, ...resolved].map((release) => [
    `${release.spotifyId}|${release.source}`, release,
  ])).values()].slice(-40);
  if (!combined.length) throw new Error("Spotify produced no verified label-watchlist matches");
  await mkdir("data", { recursive: true });
  await writeFile(
    "data/label-watchlist.json",
    `${JSON.stringify({
      updatedAt: resolved.length ? new Date().toISOString() : existing.updatedAt,
      activatedAt: existing.activatedAt || new Date().toISOString(),
      sources: labels,
      releases: combined,
    }, null, 2)}\n`,
  );
  console.log(`Saved ${combined.length} verified releases (${resolved.length} new) from ${labels.length} labels`);
} finally {
  await browser.close();
}
