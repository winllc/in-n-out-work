# ReadyRoom: WCAG 2.1 AA self-audit

**Status: self-assessment, October 2026, for ENS to review.** Not a third-party audit. It records what was checked,
how, what was fixed, and what is known to fall short, so that ENS can complete manual testing and publish the
conformance report ([`vpat-2.5-draft.md`](vpat-2.5-draft.md)).

## Scope

The ReadyRoom web application (all pages a signed-in administrator, manager or user reaches, the sign-in page and
the notice-and-consent banner), version 1.x on this branch. Out of scope: the Windows client (`powershell/`), which
has no user interface (scheduled tasks); the public API and webhooks (no user interface); e-mails (simple HTML,
not evaluated); the ENS licensing portal and documentation site.

## Method

1. **Automated, in CI on every push** (`accessibility` job, [`a11y/`](../../a11y/)): axe-core 4.13 through Playwright
   (Chromium), rules tagged WCAG 2.0 and 2.1 level A and AA, on 24 page states at two widths (1280 and 320 CSS
   pixels, the latter for reflow): sign-in, consent banner, home (as administrator, manager and user), groups, a
   group's members, user search, a person's details, metrics, org chart, profile, privacy, settings, license,
   delegates, privacy administration, audit log, integrations, scheduled reports, a group report, My Reports,
   approvals and an absence alert. The application runs with seeded data (`./gradlew a11yServer`). **Any serious or
   critical violation fails the build.** Every finding of every impact is kept as a CI artifact.
2. **Manual review of the code and templates** against each success criterion (results below).
3. **Not yet done** (for ENS): screen reader testing (NVDA with Firefox or Chrome, JAWS, VoiceOver), keyboard-only
   walk-through of every task, 200% and 400% zoom and text-spacing checks, and Windows high-contrast mode.

## What the automated checks found, and what was fixed

The first run found 8 rules failing across 42 of the 48 page checks. All are fixed; the current run has no
violations at any impact:

| Rule (impact) | Where | Fix |
|---|---|---|
| `html-has-lang` (serious) | every page | `lang="en"` on every template |
| `label` (critical) | the system-date field in the header; Tabulator header filters | a visually hidden label; filters named "Filter <column>" (`static/js/a11y.js`) |
| `aria-required-children`, `aria-required-parent` (critical) | Tabulator tables (users, reports, audit) | the scrolling body becomes the grid and owns the header row; the pager and wrappers are presentational (`a11y.js`) |
| `color-contrast` (serious) | Tabler light badges (`bg-*-lt`), coloured text, green and outline-danger buttons, calendar days of other months, empty-table text, calendar request events | darker shades of the same hues, all at least 4.5:1 (`app.css`); calendar event defaults changed (organization calendar `#a84400`, requests `#5c636a`) |
| `link-in-text-block` (serious) | links inside sentences | underlined |
| `scrollable-region-focusable` (serious) | wide tables, the calendar | scrolling tables are focusable regions named after their card; calendars drawn at full height |
| `aria-progressbar-name` (serious) | metrics, My Reports, status mix | named progress bars |
| `role-img-alt` (serious) | calendar arrow icons | hidden from assistive technology; the buttons are named |

Also fixed while reviewing: every page's `<title>` now names the page ("Audit log · ReadyRoom"; it was "ReadyRoom"
everywhere, 2.4.2); a "Skip to main content" link and a `main` landmark (2.4.1; the landmark was being replaced by
the page content); form field borders at 3:1 (1.4.11); the audit log's pager no longer links to page −1 on the first
page.

**Flagged by axe for manual review** (it cannot decide them): text colour on `<select>` elements over their
background image (the arrow) and the footer's secondary link, which need a manual contrast check; FullCalendar's
`aria-label` on day headers and `aria-labelledby` on its view (supported, but verify with screen readers).

## Results by success criterion

S = supports, P = partially supports, D = does not support, N/A = not applicable. "To verify" marks a judgement
from the code that the manual tests above must confirm.

| Criterion | | Notes |
|---|---|---|
| 1.1.1 Non-text Content (A) | P | Logo has alt text; icons are decorative font glyphs. Charts on Home and Metrics show their figures as text beside them, but the chart images themselves have no text alternative (to verify with a screen reader). |
| 1.2.1–1.2.5 Time-based media | N/A | No audio or video. |
| 1.3.1 Info and Relationships (A) | P | Labels, headings, lists and data tables (with `scope`) are marked up; Tabulator and FullCalendar are repaired by script. The org chart tree and some card layouts rely on visual grouping (to verify). |
| 1.3.2 Meaningful Sequence (A) | S | DOM order is reading order. |
| 1.3.3 Sensory Characteristics (A) | S | Instructions do not rely on shape, colour or position alone. |
| 1.3.4 Orientation (AA) | S | Responsive; no orientation lock. |
| 1.3.5 Identify Input Purpose (AA) | S | Sign-in fields carry `autocomplete` (username, current-password); other fields do not collect the user's own personal data. |
| 1.4.1 Use of Color (A) | S | Statuses are text badges; links in text are underlined; calendar events carry text titles. |
| 1.4.2 Audio Control (A) | N/A | |
| 1.4.3 Contrast (Minimum) (AA) | P | Passes axe on every page checked; `<select>` text and the footer link need a manual check; operators can configure calendar colours and the classification banner colour, which must then be chosen at 4.5:1. |
| 1.4.4 Resize Text (AA) | S (to verify) | Relative units (Tabler/Bootstrap); no fixed-height text containers known. |
| 1.4.5 Images of Text (AA) | S | Only the logo. |
| 1.4.10 Reflow (AA) | P | Checked at 320 px; navigation collapses. Wide data tables and the org chart scroll in two dimensions (allowed for data tables and diagrams), the calendar's week view is cramped (to verify). |
| 1.4.11 Non-text Contrast (AA) | P | Field and checkbox borders now 3:1; chart segments and the status-mix bar are not all 3:1 against each other (their values are also given as text). |
| 1.4.12 Text Spacing (AA) | S (to verify) | |
| 1.4.13 Content on Hover or Focus (AA) | S | Dropdowns close with Escape; tooltips are native `title` attributes. |
| 2.1.1 Keyboard (A) | P | Forms, menus, links, buttons and scroll regions are keyboard operable. Not by keyboard: picking days on the profile calendar by dragging (a status can still be set by clicking a day, and requested on the Approvals page with date fields); opening a person from a table row (names are links in most tables, not all); expanding the org chart (to verify). |
| 2.1.2 No Keyboard Trap (A) | S (to verify) | Bootstrap modals and flatpickr return focus. |
| 2.1.4 Character Key Shortcuts (A) | N/A | None. |
| 2.2.1 Timing Adjustable (A) | P | The session ends after the configured idle time (default 30 minutes, often required by STIG) without a warning or a way to extend it. A warning dialog is planned. |
| 2.2.2 Pause, Stop, Hide (A) | S | Nothing moves, blinks or updates automatically. |
| 2.3.1 Three Flashes (A) | S | |
| 2.4.1 Bypass Blocks (A) | S | Skip link and landmarks (`header`, `aside`, `main`). |
| 2.4.2 Page Titled (A) | S | |
| 2.4.3 Focus Order (A) | S (to verify) | |
| 2.4.4 Link Purpose (In Context) (A) | S | Icon-only buttons have names; repeated "Revoke"/"Delete" buttons name their item. |
| 2.4.5 Multiple Ways (AA) | S | Navigation, user search, groups list. |
| 2.4.6 Headings and Labels (AA) | S | |
| 2.4.7 Focus Visible (AA) | P | Tabler focus rings on controls and a visible outline on scroll regions; clickable table rows without a link show no focus. |
| 2.5.1 Pointer Gestures (A) | S | The calendar's drag selection has a single-click alternative. |
| 2.5.2 Pointer Cancellation (A) | S | |
| 2.5.3 Label in Name (A) | S | |
| 2.5.4 Motion Actuation (A) | N/A | |
| 3.1.1 Language of Page (A) | S | `lang="en"`. |
| 3.1.2 Language of Parts (AA) | S | English only. |
| 3.2.1 On Focus (A) | S | |
| 3.2.2 On Input (A) | P | The header's system-date picker reloads the view when a date is picked, without a separate button (managers only). |
| 3.2.3 Consistent Navigation (AA) | S | One layout. |
| 3.2.4 Consistent Identification (AA) | S | |
| 3.3.1 Error Identification (A) | P | Server-side errors are shown as text alerts (`role="alert"`); some script-driven forms report errors in toasts (live region) without marking the field. |
| 3.3.2 Labels or Instructions (A) | S | |
| 3.3.3 Error Suggestion (AA) | P | Messages say what is wrong (e.g. which recipients may not receive a report); not every field-level error suggests a fix. |
| 3.3.4 Error Prevention (Legal, Financial, Data) (AA) | P | Erasure and other destructive administrative actions ask for confirmation; revoking a token and deleting a webhook or schedule do not, but are reversible by creating them again. |
| 4.1.1 Parsing (A) | S | Templates render well-formed HTML (always satisfied under WCAG 2.2). |
| 4.1.2 Name, Role, Value (A) | P | Native controls throughout; third-party widgets (Tabulator, FullCalendar, flatpickr, the org chart) are repaired by script or partly custom (to verify with screen readers). |
| 4.1.3 Status Messages (AA) | S | Notices use `role="status"`/`"alert"`; toasts are an assertive live region. |

## Languages

The interface is English only (three message keys exist; text is in the templates). Translating it is not trivial
(several hundred strings in templates and scripts) and is deferred; it is not a WCAG requirement.

## Keeping it

- New pages: add them to `a11y/tests/axe.spec.js`. A serious or critical violation fails CI.
- New colours: at least 4.5:1 for text, 3:1 for borders and graphics; see the accessibility block in `app.css`.
- Third-party widgets: repair them in `static/js/a11y.js`, not in the library files.
