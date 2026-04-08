#!/usr/bin/env node
/**
 * Debug script: navigate to localhost:5173, capture console/network, run fetch test, screenshot
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';

const OUTPUT_DIR = join(process.cwd(), 'output', 'playwright');
await mkdir(OUTPUT_DIR, { recursive: true });

const consoleLogs = [];
const codexApiRequests = [];
const codexApiResponses = [];
const failedRequests = [];

const browser = await chromium.launch({ headless: false });
const context = await browser.newContext();
const page = await context.newPage();

// Capture console messages
page.on('console', (msg) => {
  const type = msg.type();
  const text = msg.text();
  const loc = msg.location();
  const entry = {
    type,
    text,
    url: loc?.url || '',
    line: loc?.lineNumber,
  };
  consoleLogs.push(entry);
  if (type === 'error' || type === 'warning') {
    console.log(`[CONSOLE ${type.toUpperCase()}]`, text);
  }
});

// Capture network: requests to /codex-api/
page.on('request', (req) => {
  const url = req.url();
  if (url.includes('/codex-api/')) {
    codexApiRequests.push({
      url,
      method: req.method(),
      resourceType: req.resourceType(),
    });
  }
});

page.on('response', (resp) => {
  const url = resp.url();
  if (url.includes('/codex-api/')) {
    const status = resp.status();
    codexApiResponses.push({
      url,
      status,
      statusText: resp.statusText(),
      ok: resp.ok(),
    });
    if (!resp.ok()) {
      failedRequests.push({
        url,
        status,
        statusText: resp.statusText(),
      });
    }
  }
});

page.on('requestfailed', (req) => {
  const url = req.url();
  if (url.includes('/codex-api/')) {
    failedRequests.push({
      url,
      failure: req.failure()?.errorText || 'unknown',
    });
  }
});

console.log('Navigating to http://localhost:5173/...');
await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded', timeout: 20000 });

// Wait a bit for any initial model loading / RPC calls
await page.waitForTimeout(3000);

// Execute the fetch test
console.log('Executing model/list RPC fetch...');
const fetchResult = await page.evaluate(async () => {
  try {
    const r = await fetch('/codex-api/rpc', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ method: 'model/list', params: {} }),
    });
    const d = await r.json();
    return { ok: r.ok, status: r.status, data: d };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
});

console.log('Fetch result:', JSON.stringify(fetchResult, null, 2));

// Screenshot full page
const fullPath = join(OUTPUT_DIR, 'debug-full-page.png');
await page.screenshot({ path: fullPath, fullPage: true });
console.log('Screenshot saved:', fullPath);

// Try to find and screenshot model dropdown area
const modelDropdown = await page.locator('[data-testid="model-select"], .model-select, select, [role="combobox"]').first();
if (await modelDropdown.count() > 0) {
  await modelDropdown.screenshot({ path: join(OUTPUT_DIR, 'debug-model-dropdown.png') });
  console.log('Model dropdown screenshot saved');
}

// Summary
const errors = consoleLogs.filter((e) => e.type === 'error');
const warnings = consoleLogs.filter((e) => e.type === 'warning');

console.log('\n=== SUMMARY ===');
console.log('Console errors:', errors.length);
errors.forEach((e) => console.log('  -', e.text));
console.log('Console warnings:', warnings.length);
warnings.forEach((w) => console.log('  -', w.text));
console.log('/codex-api/ requests:', codexApiRequests.length);
codexApiRequests.forEach((r) => console.log('  -', r.method, r.url));
console.log('/codex-api/ responses:', codexApiResponses.length);
codexApiResponses.forEach((r) => console.log('  -', r.status, r.url));
console.log('Failed /codex-api/ requests:', failedRequests.length);
failedRequests.forEach((f) => console.log('  -', JSON.stringify(f)));

// Write report
const report = {
  timestamp: new Date().toISOString(),
  consoleErrors: errors,
  consoleWarnings: warnings,
  codexApiRequests,
  codexApiResponses,
  failedRequests,
  fetchResult,
};
await import('node:fs/promises').then((fs) =>
  fs.writeFile(join(OUTPUT_DIR, 'debug-report.json'), JSON.stringify(report, null, 2))
);
console.log('Report saved to output/playwright/debug-report.json');

await browser.close();
