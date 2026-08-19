import { chromium } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";

const source = "https://boomkat.com/weekly-roundup";
const normalise = (value) => value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();

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
    userAgent:
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  });
  const page = await context.newPage();
  await page.goto(source, { waitUntil: "domcontentloaded", timeout: 90_000 });
  await page.waitForFunction(
    () => /Album of the week|Single of the week/i.test(document.body?.innerText || ""),
    undefined,
    { timeout: 90_000 },
  );

  const lines = (await page.locator("body").innerText())
    .split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const releases = [];
  for (let i = 0; i < lines.length; i += 1) {
    if (!/^(?:Album|Albums|Single) of the week$/i.test(lines[i])) continue;
    const artist = lines[i + 1] || "";
    const title = lines[i + 2] || "";
    if (artist && title) releases.push({ artist, title, section: lines[i] });
  }
  const unique = [...new Map(
    releases.map((item) => [`${normalise(item.artist)}|${normalise(item.title)}`, item]),
  ).values()].slice(0, 12);
  if (!unique.length) throw new Error("Boomkat produced no weekly headline releases");

  const spotifyPage = await context.newPage();
  const resolved = [];
  for (const release of unique) {
    try {
      const query = encodeURIComponent(`${release.artist} ${release.title}`);
      await spotifyPage.goto(`https://open.spotify.com/search/${query}/albums`, {
        waitUntil: "domcontentloaded",
        timeout: 30_000,
      });
      const albumLink = spotifyPage.locator('a[href*="/album/"]').first();
      await albumLink.waitFor({ state: "attached", timeout: 15_000 });
      const href = await albumLink.getAttribute("href");
      const spotifyId = href?.match(/\/album\/([^/?]+)/)?.[1];
      if (!spotifyId) throw new Error("Spotify returned no album ID");
      const cover = await spotifyArtwork(spotifyId, release.title);
      resolved.push({ ...release, spotifyId, cover });
    } catch (error) {
      console.warn(`Skipped ${release.artist} — ${release.title}: ${error.message}`);
    }
  }
  if (!resolved.length) throw new Error("Spotify produced no verified Boomkat albums");

  await mkdir("data", { recursive: true });
  await writeFile(
    "data/boomkat-weekly.json",
    `${JSON.stringify({ updatedAt: new Date().toISOString(), source, releases: resolved }, null, 2)}\n`,
  );
} finally {
  await browser.close();
}
