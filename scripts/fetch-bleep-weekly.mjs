import { chromium } from "playwright";
import { mkdir, writeFile } from "node:fs/promises";

const source = "https://bleep.com/weekly-roundup?lang=en_GB";
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
  let section = "Release of the Week";
  for (let i = start + 1; i < end; i += 1) {
    if (/^Featured Releases$/i.test(lines[i])) {
      section = "Featured Releases";
      continue;
    }
    if (!/^Artist$/i.test(lines[i]) || !lines[i + 1]) continue;
    const releaseMarker = lines
      .slice(i + 2, Math.min(i + 10, end))
      .findIndex((line) => /^ReleaseProduct$/i.test(line));
    if (releaseMarker < 0) continue;
    const marker = i + 2 + releaseMarker;
    if (!lines[marker + 1]) continue;
    releases.push({ artist: lines[i + 1], title: lines[marker + 1], section });
    i = marker + 1;
  }

  const unique = [...new Map(
    releases.map((item) => [`${item.artist.toLowerCase()}|${item.title.toLowerCase()}`, item]),
  ).values()].slice(0, 12);
  if (!unique.length) throw new Error("Bleep page produced no releases; keeping the last good feed");

  await mkdir("data", { recursive: true });
  await writeFile(
    "data/bleep-weekly.json",
    `${JSON.stringify({ updatedAt: new Date().toISOString(), source, releases: unique }, null, 2)}\n`,
  );
  console.log(`Saved ${unique.length} Bleep releases`);
} finally {
  await browser.close();
}
