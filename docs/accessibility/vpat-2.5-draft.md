# Accessibility Conformance Report — ReadyRoom (DRAFT)

**ITI VPAT® Version 2.5Rev (508 edition) — SELF-ASSESSMENT DRAFT FOR ENS REVIEW. Not for publication.**
Prepared from the automated checks and code review in [`wcag-2.1-aa-self-audit.md`](wcag-2.1-aa-self-audit.md);
manual and assistive-technology testing has not yet been done. ENS must complete the bracketed fields, run the
manual tests listed there, and adjust the conformance levels before publishing.

| | |
|---|---|
| **Name of product / version** | ReadyRoom (web application), version [release] |
| **Report date** | [date of publication] (draft October 2026) |
| **Product description** | Workforce presence and accountability: who is in, absence alerts, leave and telework approvals, reports, for on-premises and government networks. A web application (server) and a Windows client without a user interface. |
| **Contact information** | ENS Solutions, LLC — [accessibility contact e-mail] |
| **Notes** | The Windows client runs as scheduled tasks and has no user interface; this report covers the web application. The public API and webhooks have no user interface. |
| **Evaluation methods used** | Automated testing with axe-core 4.13 (WCAG 2.0/2.1 A and AA rules) through Playwright and Chromium, on 24 page states at 1280 and 320 CSS pixels, in continuous integration; manual code and template review. [ENS to add: screen reader testing with NVDA/JAWS and VoiceOver, keyboard-only testing, zoom and text-spacing testing, and the browsers used.] |

## Applicable standards and guidelines

| Standard / guideline | Included in report |
|---|---|
| Web Content Accessibility Guidelines 2.0 | Level A (Yes), Level AA (Yes), Level AAA (No) |
| Web Content Accessibility Guidelines 2.1 | Level A (Yes), Level AA (Yes), Level AAA (No) |
| Revised Section 508 standards published January 18, 2017 and corrected January 22, 2018 | Yes |

## Terms

- **Supports**: the functionality of the product has at least one method that meets the criterion without known
  defects, or meets it with equivalent facilitation.
- **Partially Supports**: some functionality of the product does not meet the criterion.
- **Does Not Support**: the majority of product functionality does not meet the criterion.
- **Not Applicable**: the criterion is not relevant to the product.

## WCAG 2.x Report

Tables 1 and 2 also document conformance with Revised Section 508 (Chapter 5 – 501.1 Scope, 504.2 Content Creation
or Editing; Chapter 6 – 602.3 Electronic Support Documentation) for web content.

### Table 1: Success Criteria, Level A

| Criteria | Conformance Level | Remarks and Explanations |
|---|---|---|
| 1.1.1 Non-text Content | Partially Supports | Images and icons have text alternatives or are decorative. Charts on the Home and Metrics pages show their values as text nearby; the chart graphics have no text alternative of their own. |
| 1.2.1 Audio-only and Video-only (Prerecorded) | Not Applicable | No audio or video content. |
| 1.2.2 Captions (Prerecorded) | Not Applicable | |
| 1.2.3 Audio Description or Media Alternative (Prerecorded) | Not Applicable | |
| 1.3.1 Info and Relationships | Partially Supports | Forms, headings, landmarks and data tables are marked up. Third-party tables and calendars are given grid semantics by script. The organization chart and some card layouts convey grouping visually. |
| 1.3.2 Meaningful Sequence | Supports | |
| 1.3.3 Sensory Characteristics | Supports | |
| 1.4.1 Use of Color | Supports | Statuses are shown as text; links in text are underlined. |
| 1.4.2 Audio Control | Not Applicable | |
| 2.1.1 Keyboard | Partially Supports | Selecting a range of days on the profile calendar requires a pointer (single days and the Approvals form are keyboard operable); some table rows open a person's page by pointer only; the organization chart's expand and collapse is pointer oriented. |
| 2.1.2 No Keyboard Trap | Supports | [ENS to confirm by keyboard testing.] |
| 2.1.4 Character Key Shortcuts | Not Applicable | No single-character shortcuts. |
| 2.2.1 Timing Adjustable | Partially Supports | Sessions end after an administrator-configured idle time (security requirement on government networks) without an advance warning or extension. |
| 2.2.2 Pause, Stop, Hide | Supports | |
| 2.3.1 Three Flashes or Below Threshold | Supports | |
| 2.4.1 Bypass Blocks | Supports | Skip link and landmarks. |
| 2.4.2 Page Titled | Supports | |
| 2.4.3 Focus Order | Supports | [ENS to confirm.] |
| 2.4.4 Link Purpose (In Context) | Supports | |
| 2.5.1 Pointer Gestures | Supports | |
| 2.5.2 Pointer Cancellation | Supports | |
| 2.5.3 Label in Name | Supports | |
| 2.5.4 Motion Actuation | Not Applicable | |
| 3.1.1 Language of Page | Supports | |
| 3.2.1 On Focus | Supports | |
| 3.2.2 On Input | Partially Supports | The system-date picker (managers) changes the view as soon as a date is chosen. |
| 3.3.1 Error Identification | Partially Supports | Errors are described in text; some script-driven forms do not mark the field in error. |
| 3.3.2 Labels or Instructions | Supports | |
| 4.1.1 Parsing | Supports | Under WCAG 2.2 this criterion is always satisfied. |
| 4.1.2 Name, Role, Value | Partially Supports | Native controls throughout; third-party widgets (tables, calendars, date pickers, org chart) are repaired by script and partly custom. [ENS to confirm with screen readers.] |

### Table 2: Success Criteria, Level AA

| Criteria | Conformance Level | Remarks and Explanations |
|---|---|---|
| 1.2.4 Captions (Live) | Not Applicable | |
| 1.2.5 Audio Description (Prerecorded) | Not Applicable | |
| 1.3.4 Orientation | Supports | |
| 1.3.5 Identify Input Purpose | Supports | |
| 1.4.3 Contrast (Minimum) | Partially Supports | All checked pages pass automated contrast testing; select boxes and the footer link need manual confirmation. Calendar and banner colours an administrator configures must be chosen with sufficient contrast. |
| 1.4.4 Resize Text | Supports | [ENS to confirm at 200%.] |
| 1.4.5 Images of Text | Supports | |
| 1.4.10 Reflow | Partially Supports | Pages reflow at 320 CSS pixels; data tables and the organization chart scroll in two dimensions, as the exception for data tables and diagrams allows; the calendar's week view is cramped. |
| 1.4.11 Non-text Contrast | Partially Supports | Form controls meet 3:1; some chart segments do not (their values are also given as text). |
| 1.4.12 Text Spacing | Supports | [ENS to confirm.] |
| 1.4.13 Content on Hover or Focus | Supports | |
| 2.4.5 Multiple Ways | Supports | |
| 2.4.6 Headings and Labels | Supports | |
| 2.4.7 Focus Visible | Partially Supports | Clickable table rows without a link show no focus indicator. |
| 3.1.2 Language of Parts | Supports | |
| 3.2.3 Consistent Navigation | Supports | |
| 3.2.4 Consistent Identification | Supports | |
| 3.3.3 Error Suggestion | Partially Supports | |
| 3.3.4 Error Prevention (Legal, Financial, Data) | Partially Supports | Destructive administrative actions such as erasing a person's data ask for confirmation; some (revoking a token, deleting a webhook or schedule) do not. |
| 4.1.3 Status Messages | Supports | |

### Table 3: Success Criteria, Level AAA

Not evaluated.

## Revised Section 508 Report

### Chapter 3: Functional Performance Criteria (FPC)

| Criteria | Conformance Level | Remarks and Explanations |
|---|---|---|
| 302.1 Without Vision | Partially Supports | Semantic markup and names throughout; not yet tested with screen readers; see 1.1.1, 2.1.1 and 4.1.2. |
| 302.2 With Limited Vision | Partially Supports | Text resizes and reflows; contrast as in 1.4.3 and 1.4.11. |
| 302.3 Without Perception of Color | Supports | |
| 302.4 Without Hearing | Not Applicable | No audio. |
| 302.5 With Limited Hearing | Not Applicable | |
| 302.6 Without Speech | Not Applicable | No speech input. |
| 302.7 With Limited Manipulation | Partially Supports | See 2.1.1. |
| 302.8 With Limited Reach and Strength | Supports | |
| 302.9 With Limited Language, Cognitive, and Learning Abilities | Partially Supports | Plain labels and consistent layout; session timeouts without warning (2.2.1). |

### Chapter 4: Hardware

Not Applicable: ReadyRoom is software.

### Chapter 5: Software

| Criteria | Conformance Level | Remarks and Explanations |
|---|---|---|
| 501.1 Scope – Incorporation of WCAG 2.0 AA | See WCAG 2.x section | The web application. |
| 502 Interoperability with Assistive Technology | Not Applicable | ReadyRoom is web content, not platform software; the Windows client has no user interface. |
| 503.2 User Preferences | Supports | Uses the browser's and platform's settings for colour, contrast, font and focus; does not override them. |
| 503.3 Alternative User Interfaces | Not Applicable | |
| 503.4 User Controls for Captions and Audio Description | Not Applicable | |
| 504 Authoring Tools | Not Applicable | ReadyRoom does not produce web content for others (notes are plain text). |

### Chapter 6: Support Documentation and Services

| Criteria | Conformance Level | Remarks and Explanations |
|---|---|---|
| 602.2 Accessibility and Compatibility Features | Partially Supports | This report and the self-audit describe them; [ENS to add a user-facing accessibility page]. |
| 602.3 Electronic Support Documentation | [ENS to evaluate] | Documentation is published as Markdown on the ENS documentation site. |
| 602.4 Alternate Formats for Non-Electronic Support Documentation | [ENS to complete] | |
| 603.2 Information on Accessibility and Compatibility Features | [ENS to complete] | Support services' information. |
| 603.3 Accommodation of Communication Needs | [ENS to complete] | |

## Legal disclaimer

[ENS to add its standard disclaimer.] This draft reports a self-assessment and makes no representation of
conformance until reviewed and published by ENS Solutions, LLC. "VPAT" is a registered service mark of the
Information Technology Industry Council (ITI).
