import { chromium } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";

const indexUrl = "https://daily.bandcamp.com/genres/electronic";
const normalise = (value) => value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
const verifiedSpotifyAlbums = new Map([
  ["the bug dis fig|ladybug 1", "0U171DRtrbqXZONDIPd14F"],
  ["hvl|formation", "70S5dpPzjDZJ6WzftyGjL4"],
  ["rekab|subtle beginnings", "6Pc0xUcMdQHcITAGRWYyRy"],
]);

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
    timezoneId: "Europe/London",
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36",
  });
  const page = await context.newPage();
  await page.goto(indexUrl, { waitUntil: "domcontentloaded", timeout: 90_000 });
  const articleLink = page.locator('a[href*="/best-electronic/"]')
    .filter({ hasText: /The Best Electronic Music on Bandcamp/i }).first();
  await articleLink.waitFor({ state: "attached", timeout: 30_000 });
  const href = await articleLink.getAttribute("href");
  if (!href) throw new Error("Bandcamp Daily supplied no current electronic article");
  const source = new URL(href, indexUrl).href;
  await page.goto(source, { waitUntil: "domcontentloaded", timeout: 90_000 });

  const picks = await page.locator("h3").evaluateAll((headings) => headings.map((heading) => {
    const artist = heading.textContent?.trim() || "";
    let node = heading.nextElementSibling;
    while (node) {
      const link = node.matches?.('a[href*="bandcamp.com"]')
        ? node
        : node.querySelector?.('a[href*="bandcamp.com"]');
      const title = link?.textContent?.trim() || "";
      if (title) return { artist, title };
      if (node.matches?.("h3")) break;
      node = node.nextElementSibling;
    }
    return null;
  }).filter(Boolean));

  const unique = [...new Map(picks.map((pick) => [
    `${normalise(pick.artist)}|${normalise(pick.title)}`, pick,
  ])).values()].slice(0, 12);
  if (!unique.length) throw new Error("Bandcamp Daily produced no electronic picks");

  const spotifyPage = await context.newPage();
  const resolved = [];
  for (const pick of unique) {
    try {
      const key = `${normalise(pick.artist)}|${normalise(pick.title)}`;
      let spotifyId = verifiedSpotifyAlbums.get(key);
      if (!spotifyId) {
        const query = encodeURIComponent(`${pick.artist} ${pick.title}`);
        await spotifyPage.goto(`https://open.spotify.com/search/${query}/albums`, {
          waitUntil: "domcontentloaded", timeout: 30_000,
        });
        const albumLink = spotifyPage.locator('a[href*="/album/"]').first();
        await albumLink.waitFor({ state: "attached", timeout: 15_000 });
        spotifyId = (await albumLink.getAttribute("href"))?.match(/\/album\/([^/?]+)/)?.[1];
      }
      if (!spotifyId) throw new Error("Spotify returned no album ID");
      const cover = await spotifyArtwork(spotifyId, pick.title);
      resolved.push({ ...pick, spotifyId, cover });
    } catch (error) {
      console.warn(`Skipped ${pick.artist} — ${pick.title}: ${error.message}`);
    }
  }
  if (!resolved.length) throw new Error("Spotify produced no verified Bandcamp Daily matches");
  await mkdir("data", { recursive: true });
  await writeFile(
    "data/bandcamp-electronic.json",
    `${JSON.stringify({ updatedAt: new Date().toISOString(), source, releases: resolved }, null, 2)}\n`,
  );
} finally {
  await browser.close();
}
