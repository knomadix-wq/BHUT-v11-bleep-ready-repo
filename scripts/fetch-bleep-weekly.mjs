import { chromium } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";

const source = "https://bleep.com/weekly-roundup?lang=en_GB";
const verifiedSpotifyAlbums = new Map([
  ["topdown dialectic|false lp a", "1R570SkqASVYyKJJQAzV5v"],
  ["mos def|the ecstatic", "5Oa2WgO3Jfuw2IKYrZNzTi"],
]);
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
    () => document.body?.innerText.includes("Release of the Week"),
    undefined,
    { timeout: 90_000 },
  );

  const text = await page.locator("body").innerText();
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const start = lines.findIndex((line) => /^Release of the Week$/i.test(line));
  const endCandidate = lines.findIndex(
    (line, index) => index > start && /^Download of the Week$/i.test(line),
  );
  if (start < 0) throw new Error("Bleep page has no Release of the Week section");
  const end = endCandidate > start ? endCandidate : lines.length;

  const releases = [];
  const featuredStart = lines.findIndex(
    (line, index) => index > start && /^Featured Releases$/i.test(line),
  );

  // The live Bleep page renders the lead item as artist, title, label, date.
  if (featuredStart > start + 2) {
    releases.push({
      artist: lines[start + 1],
      title: lines[start + 2],
      section: "Release of the Week",
    });
  }

  // Featured releases are rendered as repeated artist, title, label triplets.
  if (featuredStart > start) {
    const featuredEndCandidate = lines.findIndex(
      (line, index) => index > featuredStart && /^View More$/i.test(line),
    );
    const featuredEnd = featuredEndCandidate > featuredStart ? featuredEndCandidate : end;
    const featuredLines = lines
      .slice(featuredStart + 1, featuredEnd)
      .filter((line) => !/^Unavailable$/i.test(line));
    for (let i = 0; i + 2 < featuredLines.length; i += 3) {
      releases.push({
        artist: featuredLines[i],
        title: featuredLines[i + 1],
        section: "Featured Releases",
      });
    }
  }

  const unique = [...new Map(
    releases.map((item) => [`${item.artist.toLowerCase()}|${item.title.toLowerCase()}`, item]),
  ).values()].slice(0, 4);
  if (!unique.length) {
    console.error("Bleep section preview:", lines.slice(start, Math.min(start + 80, end)).join(" | "));
    throw new Error("Bleep page produced no releases; keeping the last good feed");
  }

  const spotifyPage = await context.newPage();
  const resolved = [];
  for (const release of unique) {
    const releaseKey = `${release.artist.toLowerCase()}|${release.title.toLowerCase()}`;
    const verifiedId = verifiedSpotifyAlbums.get(releaseKey);
    const query = encodeURIComponent(`${release.artist} ${release.title}`);
    try {
      let spotifyId = verifiedId;
      if (!spotifyId) {
        await spotifyPage.goto(`https://open.spotify.com/search/${query}/albums`, {
          waitUntil: "domcontentloaded",
          timeout: 30_000,
        });
        const albumLink = spotifyPage.locator('a[href*="/album/"]').first();
        await albumLink.waitFor({ state: "attached", timeout: 15_000 });
        const href = await albumLink.getAttribute("href");
        spotifyId = href?.match(/\/album\/([^/?]+)/)?.[1];
        if (!spotifyId) throw new Error("Spotify returned no album ID");
      }

      await spotifyPage.goto(`https://open.spotify.com/album/${spotifyId}`, {
        waitUntil: "domcontentloaded",
        timeout: 60_000,
      });
      const cover = await spotifyPage.locator('meta[property="og:image"]').getAttribute("content");
      resolved.push({ ...release, spotifyId, cover: cover || null });
      console.log(`Resolved ${release.artist} — ${release.title} to ${spotifyId}`);
    } catch (error) {
      console.warn(`Skipped ${release.artist} — ${release.title}: ${error.message}`);
    }
  }
  await spotifyPage.close();
  if (!resolved.length) throw new Error("Spotify produced no verified album IDs; keeping the last good feed");

  await mkdir("data", { recursive: true });
  await writeFile(
    "data/bleep-weekly.json",
    `${JSON.stringify({ updatedAt: new Date().toISOString(), source, releases: resolved }, null, 2)}\n`,
  );
  console.log(`Saved ${resolved.length} verified Bleep releases`);
} finally {
  await browser.close();
}
