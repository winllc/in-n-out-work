// @ts-check
// Runs against a ReadyRoom started with `./gradlew a11yServer` (seeded test data, http://localhost:8199).
// A11Y_BASE_URL points it elsewhere. CI: the "accessibility" job in .github/workflows/ci.yml.
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'results/playwright.json' }]],
  use: {
    baseURL: process.env.A11Y_BASE_URL || 'http://localhost:8199',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    // Reflow (WCAG 1.4.10): the same pages at 320 CSS pixels wide.
    { name: 'narrow', use: { ...devices['Desktop Chrome'], viewport: { width: 320, height: 800 } } },
  ],
});
