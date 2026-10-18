// UI smoke test + screenshots. Read-only: it navigates, opens drawers and seeks video, and never
// approves, edits, rejects, generates or exports anything.
//
//   BASE_URL=http://localhost:3000 API_URL=http://localhost:8080 PAPER_ID=P-0001 node scripts/ui_smoke_test.js
// SMOKE_DISABLE_CORS=1 is only for a frontend on another port: the backend's CORS allows :3000.
const { chromium } = require("/home/yjr/steerproof/render-service/node_modules/playwright");
const path = require("path");
const fs = require("fs");

const BASE_URL = (process.env.BASE_URL || "http://localhost:3000").replace(/\/$/, "");
const API_URL = (process.env.API_URL || "http://localhost:8080").replace(/\/$/, "");
const PAPER_ID = process.env.PAPER_ID || "P-0001";
const debugDir = path.resolve(__dirname, "../storage/debug");

const results = [];
function pass(name, detail = "") {
  results.push({ name, ok: true });
  console.log(`  ✓ ${name}${detail ? ` (${detail})` : ""}`);
}
function fail(name, detail = "") {
  results.push({ name, ok: false });
  console.log(`  ✗ ${name}${detail ? ` (${detail})` : ""}`);
}
function check(cond, name, detail) {
  (cond ? pass : fail)(name, detail);
  return cond;
}

async function shot(page, name) {
  const p = path.join(debugDir, `ui-${name}.png`);
  // The navbar is sticky; a full-page shot taken while scrolled paints it mid-page
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: p, fullPage: true });
  console.log(`  saved ${p}`);
}

async function errorToasts(page) {
  return page.$$eval('[data-testid="toast-error"]', (els) => els.map((e) => e.textContent.trim()));
}

async function main() {
  fs.mkdirSync(debugDir, { recursive: true });
  console.log(`=== SteerProof UI smoke test: ${BASE_URL} (API ${API_URL}), paper ${PAPER_ID} ===`);

  const assets = await fetch(`${API_URL}/api/papers/${PAPER_ID}/assets`).then((r) => r.json());
  const carousel = assets.find((a) => a.type === "CAROUSEL");

  const browser = await chromium.launch({
    headless: true,
    executablePath: fs.existsSync("/usr/bin/chromium") ? "/usr/bin/chromium" : undefined,
    args: ["--no-sandbox", "--disable-setuid-sandbox", ...(process.env.SMOKE_DISABLE_CORS === "1" ? ["--disable-web-security"] : [])],
  });
  const context = await browser.newContext({ viewport: { width: 1440, height: 950 } });
  const page = await context.newPage();
  // Pre-set a reviewer so the name prompt doesn't cover the page. Nothing is written under it.
  await page.addInitScript(() => localStorage.setItem("steerproof_reviewer_name", "Test Reviewer"));

  try {
    // 1. Home
    console.log("\n[1] Home");
    await page.goto(`${BASE_URL}/`, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="paper-row"]', { timeout: 15000 });
    const rows = await page.$$eval('[data-testid="paper-row"]', (els) =>
      els.map((e) => ({ href: e.getAttribute("href"), text: e.textContent }))
    );
    check(rows.length > 0, "papers listed", `${rows.length} row(s)`);
    check(rows.every((r) => r.href && !r.href.endsWith("/undefined")), "paper links resolve to real IDs");
    check(!rows.some((r) => r.text.includes("Clinical Research Paper")), "no placeholder titles");
    await shot(page, "home");

    // 2. Pipeline
    console.log("\n[2] Pipeline page");
    await page.goto(`${BASE_URL}/papers/${PAPER_ID}`, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="stage-PARSE"]', { timeout: 15000 });
    const stageStates = await page.$$eval('[data-testid^="stage-"]', (els) =>
      els.filter((e) => e.getAttribute("data-status")).map((e) => `${e.dataset.testid.slice(6)}=${e.getAttribute("data-status")}`)
    );
    console.log(`  stages: ${stageStates.join(", ")}`);
    check(
      ["PARSE", "EXTRACT", "VERIFY"].every((s) => stageStates.includes(`${s}=DONE`)),
      "parse/extract/verify shown as done"
    );
    check((await errorToasts(page)).length === 0, "no error toast on pipeline page", (await errorToasts(page)).join(" | "));
    await shot(page, "pipeline");

    // 3a. Claims: select a claim, highlight visible
    console.log("\n[3] Claims console (a)");
    await page.goto(`${BASE_URL}/papers/${PAPER_ID}/claims`, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="claim-card"]', { timeout: 15000 });
    const cards = await page.$$('[data-testid="claim-card"]');
    // Click the card body text (the heading), never the Approve/Edit/Reject buttons
    await (await cards[Math.min(1, cards.length - 1)].$("h4")).click();
    const hl = await page.waitForSelector('[data-testid="bbox-highlight"]', { state: "visible", timeout: 8000 }).catch(() => null);
    const hlBox = hl && (await hl.boundingBox());
    check(!!hlBox && hlBox.width > 4 && hlBox.height > 4, "(a) bbox highlight visible on page image", hlBox ? `${Math.round(hlBox.width)}×${Math.round(hlBox.height)}px` : "missing");
    await shot(page, "claims");

    // 3b/3c. Assets: carousel slide 2
    console.log("\n[4] Assets console (b, c)");
    await page.goto(`${BASE_URL}/papers/${PAPER_ID}/assets`, { waitUntil: "networkidle" });
    await page.waitForSelector('[data-testid="slide-dot-2"]', { timeout: 15000 });
    await page.click('[data-testid="slide-dot-2"]');

    // (c) the slide label has no claim overlay; clicking where it is opens no claim drawer
    const label = (carousel?.content?.blocks || []).find((b) => b.blockId === "slide2.label");
    check((await page.$('[data-block-id="slide2.label"]')) === null, "(c) slide label is not claim-clickable");
    if (label) {
      const img = await page.$('img[alt="Slide 2"]');
      const box = await img.boundingBox();
      await page.mouse.click(box.x + ((label.x + label.w / 2) / 1080) * box.width, box.y + ((label.y + label.h / 2) / 1350) * box.height);
      await page.waitForTimeout(300);
      const kind = await page.$eval('[data-testid="source-drawer"]', (d) => d.querySelector('[data-testid="drawer-content"]')?.getAttribute("data-kind") || "none");
      const evidence = await page.$('[data-testid="claim-evidence"]');
      check(!evidence && (kind === "none" || kind === "brand"), "(c) clicking the label opens no claim drawer", `drawer=${kind}`);
      await shot(page, "assets-label");
    } else {
      fail("(c) slide2.label block present in render metadata");
    }

    // (b) the slide 2 headline opens a drawer with a real quote and a page image
    const headline = await page.$('[data-block-id="slide2.headline"]');
    if (check(!!headline, "(b) slide 2 headline is clickable")) {
      const kind = await headline.getAttribute("data-kind");
      await headline.click();
      if (kind === "claim") {
        const quoteEl = await page.waitForSelector('[data-testid="claim-quote"]', { timeout: 8000 }).catch(() => null);
        const quote = quoteEl ? (await quoteEl.textContent()).trim() : "";
        check(quote.length > 20, "(b) drawer shows the exact quote", quote.slice(0, 70) + "…");
        const imgOk = await page
          .waitForFunction(() => {
            const i = document.querySelector('[data-testid="source-drawer"] [data-testid="page-highlight"] img');
            return i && i.complete && i.naturalWidth > 0;
          }, null, { timeout: 8000 })
          .then(() => true)
          .catch(() => false);
        check(imgOk, "(b) drawer shows the page image");
        check(!!(await page.$('[data-testid="source-drawer"] [data-testid="bbox-highlight"]')), "(b) page image has the bbox highlight");
        const notFound = await page.$eval('[data-testid="source-drawer"]', (d) => /not found/i.test(d.textContent));
        check(!notFound, "(b) no 'not found' claim in drawer");
      } else {
        fail("(b) slide 2 headline is a claim block", `kind=${kind}`);
      }
    }
    await shot(page, "assets");

    // (d) reel: clicking scene 3 seeks the video to scene 3
    console.log("\n[5] Reel tab (d)");
    await page.click('[data-testid="tab-reel"]');
    await page.waitForSelector('[data-testid="reel-video"]', { timeout: 10000 });
    await page.waitForFunction(() => document.querySelector('[data-testid="reel-video"]').readyState >= 1, null, { timeout: 15000 });
    await page.waitForFunction(
      () => document.querySelector('[data-testid="reel-scene"][data-scene="3"]')?.getAttribute("data-start") !== "",
      null,
      { timeout: 15000 }
    );
    const scene3Start = Number(await page.getAttribute('[data-testid="reel-scene"][data-scene="3"]', "data-start"));
    await page.click('[data-testid="reel-scene"][data-scene="3"]');
    await page.waitForTimeout(500);
    const t = await page.$eval('[data-testid="reel-video"]', (v) => v.currentTime);
    check(scene3Start > 0 && Math.abs(t - scene3Start) < 0.5, "(d) scene 3 click seeks the video", `currentTime=${t.toFixed(2)}s, scene 3 starts ${scene3Start.toFixed(2)}s`);
    const active = await page.getAttribute('[data-testid="reel-scene"][data-scene="3"]', "data-active");
    check(active === "true", "(d) scene 3 highlighted as playing");
    const drawerKind = await page.$eval('[data-testid="drawer-content"]', (d) => d.getAttribute("data-kind")).catch(() => null);
    check(!!drawerKind, "(d) scene click opens the source drawer", `kind=${drawerKind}`);
    await shot(page, "reel");

    // 6. Export page (view only, no export click)
    console.log("\n[6] Export page");
    await page.goto(`${BASE_URL}/papers/${PAPER_ID}/export`, { waitUntil: "networkidle" });
    await page.waitForSelector("h1", { timeout: 10000 });
    pass("export page loads");
    await shot(page, "export");
  } catch (err) {
    fail("unexpected error", err.message);
  } finally {
    await browser.close();
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  if (failed.length) {
    console.log("FAILED: " + failed.map((f) => f.name).join("; "));
    process.exitCode = 1;
  }
}

main();
