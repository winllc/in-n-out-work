// @ts-check
// Every main page of ReadyRoom, signed in as the role that sees most of it, checked with axe-core against the
// WCAG 2.0 and 2.1 level A and AA rules. Serious and critical violations fail the run; every finding, whatever its
// impact, is written to results/axe-<page>.json for the conformance report (docs/accessibility/).
const fs = require('fs');
const path = require('path');
const { test, expect } = require('@playwright/test');
const { AxeBuilder } = require('@axe-core/playwright');

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const FAILING = new Set(['serious', 'critical']);
const ENGINEERING = 'cn=Engineering,ou=Groups,dc=winllc,dc=com';
const BOB = 'cn=Bob Barker,ou=Users,dc=winllc,dc=com';

async function signIn(page, username) {
  await page.goto('/login');
  // The notice-and-consent banner comes first when it is on.
  if (page.url().includes('/consent')) {
    await page.getByRole('button', { name: /agree|accept|acknowledge/i }).click();
    await page.waitForURL(/\/login/);
  }
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill('password');
  await Promise.all([page.waitForURL(/\/app/), page.locator('form button[type="submit"]').first().click()]);
}

async function check(page, name, testInfo) {
  // Let scripts draw tables, calendars and charts first.
  await page.waitForLoadState('networkidle');
  // And no transition or animation still running: a button changing colour would be measured half-way.
  await page.evaluate(() => Promise.all(document.getAnimations()
    .filter(a => a.effect && a.effect.getComputedTiming().iterations !== Infinity)
    .map(a => a.finished.catch(() => undefined))));
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  const dir = path.join(__dirname, '..', 'results');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `axe-${testInfo.project.name}-${name}.json`),
    JSON.stringify({ url: page.url(), violations: results.violations, incomplete: results.incomplete }, null, 2));
  const failing = results.violations.filter(v => FAILING.has(v.impact));
  for (const v of results.violations) {
    console.log(`[${testInfo.project.name}] ${name}: ${v.impact} ${v.id} (${v.nodes.length}) ${v.help}`);
  }
  expect(failing.map(v => `${v.impact} ${v.id}: ${v.help} — ${v.nodes.slice(0, 3).map(n => n.target.join(' ')).join(' | ')}`),
    `serious or critical accessibility violations on ${name}`).toEqual([]);
}

test.describe('signed out', () => {
  test('consent banner', async ({ page }, testInfo) => {
    await page.goto('/login');
    await check(page, 'consent', testInfo);
  });

  test('login', async ({ page }, testInfo) => {
    await page.goto('/login');
    if (page.url().includes('/consent')) {
      await page.getByRole('button', { name: /agree|accept|acknowledge/i }).click();
      await page.waitForURL(/\/login/);
    }
    await check(page, 'login', testInfo);
  });
});

test.describe('administrator', () => {
  test.beforeEach(async ({ page }) => signIn(page, 'carol'));

  const pages = {
    'home': '/app/home',
    'groups': '/app/groups',
    'group-members': `/app/users/${encodeURIComponent(ENGINEERING)}`,
    'user-search': '/app/user/search',
    'user-details': `/app/user/details/${encodeURIComponent(BOB)}`,
    'metrics': '/app/metrics',
    'org-chart': '/app/orgchart',
    'profile': '/app/profile',
    'privacy': '/app/privacy',
    'settings': '/app/settings',
    'license': '/app/admin/license',
    'delegates': '/app/admin/delegates',
    'admin-privacy': '/app/admin/privacy',
    'audit-log': '/app/admin/audit',
    'integrations': '/app/admin/integrations',
    'scheduled-reports': '/app/reports/schedules',
  };
  for (const [name, url] of Object.entries(pages)) {
    test(name, async ({ page }, testInfo) => {
      const response = await page.goto(url);
      expect(response?.status(), `${url} answered`).toBeLessThan(400);
      await check(page, name, testInfo);
    });
  }

  test('group-report', async ({ page }, testInfo) => {
    await page.goto(`/app/users/${encodeURIComponent(ENGINEERING)}`);
    // The report form takes its dates from a date-range picker; post the same fields directly.
    const today = new Date();
    const from = new Date(today.getTime() - 7 * 86400000);
    await page.evaluate(([group, start, end]) => {
      const form = document.querySelector('form[action$="/app/group/report"]');
      form.querySelector('input[name="groupDn"]').value = group;
      form.querySelector('input[name="fromDate"]').value = start;
      form.querySelector('input[name="toDate"]').value = end;
      form.submit();
    }, [ENGINEERING, from.toISOString(), today.toISOString()]);
    await page.waitForURL(/\/app\/group\/report/);
    await check(page, 'group-report', testInfo);
  });
});

test.describe('manager', () => {
  test.beforeEach(async ({ page }) => signIn(page, 'alice'));

  for (const [name, url] of Object.entries({
    'manager-home': '/app/home',
    'my-reports': '/app/user/reports',
    'approvals': '/app/approvals',
  })) {
    test(name, async ({ page }, testInfo) => {
      const response = await page.goto(url);
      expect(response?.status(), `${url} answered`).toBeLessThan(400);
      await check(page, name, testInfo);
    });
  }

  test('absence-alert', async ({ page }, testInfo) => {
    await page.goto('/app/home');
    const link = page.locator('a[href*="/app/notifications/id/"]').first();
    test.skip(await link.count() === 0, 'no absence alert linked from the home page');
    // It is in the notifications menu; open it directly.
    await page.goto(await link.getAttribute('href'));
    await check(page, 'absence-alert', testInfo);
  });
});

test.describe('user', () => {
  test.beforeEach(async ({ page }) => signIn(page, 'bob'));

  test('user-home', async ({ page }, testInfo) => {
    await page.goto('/app/home');
    await check(page, 'user-home', testInfo);
  });
});
