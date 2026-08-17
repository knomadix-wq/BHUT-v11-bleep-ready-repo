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
  ).values()].slice(0, 12);
  if (!unique.length) {
    console.error("Bleep section preview:", lines.slice(start, Math.min(start + 80, end)).join(" | "));
    throw new Error("Bleep page produced no releases; keeping the last good feed");
  }

  await mkdir("data", { recursive: true });
  await writeFile(
    "data/bleep-weekly.json",
    `${JSON.stringify({ updatedAt: new Date().toISOString(), source, releases: unique }, null, 2)}\n`,
  );
  console.log(`Saved ${unique.length} Bleep releases`);
} finally {
  await browser.close();
}
