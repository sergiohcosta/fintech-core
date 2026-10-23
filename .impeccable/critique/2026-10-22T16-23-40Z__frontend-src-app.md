---
target: toda o leiaute do site
total_score: 25
max_score: 40
na_heuristics: 
p0_count: 1
p1_count: 2
target_identity: "file:/home/sergio/projetos/fintech-core/frontend/src/app"
timestamp: 2026-10-22T16-23-40Z
slug: frontend-src-app
---
# Heuristics Scoring Guide

| # | Heuristic | Score | Key Issue |
|---|-----------|:---:|-----------|
| 1 | Visibility of System Status | 3/4 | Dashboards and cards provide clear statuses, but invoice payment lacks post-payment balance feedback. |
| 2 | Match System / Real World | 3/4 | Core concepts speak everyday household language; residual backend jargon leaks into views (e.g. "Double-entry", "RFC 5545", "Taxonomia Padrão"). |
| 3 | User Control and Freedom | 2/4 | Double-entry transfers locked from inline edits; no "Undo" for bulk actions; payment cannot be undone. |
| 4 | Consistency and Standards | 2/4 | Palette fragmentation (hardcoded #1976d2, #3F51B5, #6750a4 bypass tokens); mobile layout pattern diverges between transactions and invoices. |
| 5 | Error Prevention | 3/4 | Robust hash duplicate checks and cross-field transfer prevention, but invoice payment allows selecting accounts without verifying liquidity. |
| 6 | Recognition Rather Than Recall | 3/4 | Clear category icons and active filter chips; invoice payment forces recall of source account cash balance. |
| 7 | Flexibility and Efficiency | 3/4 | Ergonomic mobile quick-entry sheet and "Salvar e lançar outra"; lacks desktop keyboard shortcuts (CMD+K, N) and range multi-select. |
| 8 | Aesthetic and Minimalist Design | 2/4 | High visual noise floor in transaction tables; multi-colored chip confetti; side-tab borders and clipped top accents. |
| 9 | Error Recovery | 2/4 | Negative budget cycle turns stark red without explaining the blowout cause or proposing corrective tradeoffs. |
| 10 | Help and Documentation | 2/4 | Helpful Day-Zero wizard; missing inline tooltips explaining daily allowance math and liquid vs net worth distinctions. |
| **Total** | | **25/40** | **Acceptable (20–27)** |

## Design Specificity Verdict

**LLM Assessment:** The application exhibits "emerging specificity with bipolar polish." When leaning into its core premise—family financial peace through daily clarity—it succeeds: the Dashboard Hero (`availableToSpend` + `dailyAllowance`), the Credit Card Synopsis, and the Mobile Quick Sheet feel intentional, tactile, and tranquil. However, across ~60% of secondary views (Invoices, Accounts, Categories, Imports), the UI abruptly drops into desktop-centric `mat-table` grids, un-tokenized legacy palettes (`#1976d2`, `#3F51B5`, `#6750a4`), and residual scaffolding. Furthermore, despite the name "Nosso Dinheirinho", the interface currently lacks joint-user presence or partner attribution.

**Deterministic Scan:** Automated detector flagged 13 issues across 9 files:
- 2 critical WCAG AA contrast failures (`#bdbdbd` at 1.9:1 in `category-list.html` and `#e65100` at 3.8:1 in `invoice-pay-dialog.html`).
- 4 side-tab accent border anti-patterns (`border-left: 3px/4px` in `account-form.scss`, `register.scss`, `transaction-form.scss`, and `transaction-list.scss`).
- 1 clipped border-accent on rounded container (`border-top: 4px` on `border-radius: 12px` in `transaction-form.scss`).
- 1 true positive font override (`font-family: Roboto` hardcoded on `.container` in `app.scss`).
- 5 false positives (regex matched `Roboto` in `Roboto Mono` tabular currency fonts, which are intentional).

**Visual Overlays:** Headless environment without active desktop display or DevTools CDP endpoint; automated scan results verified directly via AST/regex CLI detector.

## Overall Impression
Nosso Dinheirinho has strong mechanical foundations and brilliant anchor concepts (Daily Allowance, Card Synopsis, Quick Sheet), but still suffers from backend-developer visual defaults on secondary screens. Moving from "good functional software" to "award-winning consumer fintech" requires replacing dense data grids with responsive card layouts, harmonizing colors under Material 3 tokens, and bringing the collaborative "couple" experience to life.

## What's Working
1. **Daily Allowance Hero (`availableToSpend` + `dailyAllowance`):** Instantly answers the primary household question in <5s with zero mental arithmetic.
2. **Mobile Quick-Entry Sheet (`TransactionQuickSheetComponent`):** Ergonomic thumb-zone design with drag handle, oversized amount input, and fast date toggles.
3. **Credit Card Synopsis Cards (`card-synopsis-card`):** Synthesizes limits, open/closed cycles, and committed balances into a unified, tranquil snapshot.

## Priority Issues
- **[P0] System-Wide Palette Fragmentation & Legacy Indigo Leaks:** Hardcoded legacy hex values (`#1976d2`, `#3F51B5`, `#6750a4`) bypass brand tokens (`#00838f`) across forms and tables. (*Fix: Replace with semantic tokens; Command: `/impeccable colorize`*).
- **[P1] Responsive Abandonment in Secondary Screens:** `invoice-list`, `invoice-detail`, and `category-list` force rigid multi-column tables on mobile. (*Fix: Transform into responsive card layouts; Command: `/impeccable layout`*).
- **[P1] High-Stakes Anxiety in Invoice Payment & Deficit States:** `invoice-pay-dialog` lacks balance previews; negative cycles trigger stark red alarms without diagnostic guidance. (*Fix: Add before/after balance previews and supportive recovery copy; Command: `/impeccable clarify`*).
- **[P2] Residual Scaffolding & AI-Tells:** Unilateral side-tab borders, clipped top accents on rounded corners, and dead CSS in `category-list.scss`. (*Fix: Distill styles into clean M3 surface outlines; Command: `/impeccable distill`*).
- **[P2] The "Invisible Partner" (Absence of Collaborative UX):** No partner attribution tags or shared review flows. (*Fix: Add partner pills and joint milestone celebrations; Command: `/impeccable shape`*).

## Persona Red Flags
- **Alex (Power User):** No keyboard shortcuts (`CMD+K`, `N`); lacks range multi-select (`Shift+Click`) in transaction tables.
- **Jordan (First-Timer):** Intimidating technical jargon (*"Double-Entry"*, *"RFC 5545 RRULE"*, *"Taxonomia Padrão"*); lacks starter pack onboarding presets.
- **Mariana & Carlos (Co-managing Couple):** Attribution void (cannot see who incurred which expense); no collaborative monthly closing checkpoint.

## Minor Observations
- Inconsistent currency decimal styling in `budget-cycle-current.html`.
- Profile avatar in `shell.scss` introduces an un-tokenized purple gradient (`#4f46e5`).
- Quick sheet drag handle lacks spring release animation.
- Archived categories lack direct unarchive action in row.

## Questions to Consider
1. What if transaction items featured a subtle partner avatar or "Lançado por [Nome]" pill to reinforce domestic teamwork?
2. When the daily allowance drops below zero, what if the UI proposed an intelligent trade-off ("Segurar R$ 30 em Lazer por 2 dias") rather than a stark red alarm?
3. Could the 3 separate transaction views (List, Calendar, Timeline) merge into a fluid, unified stream?
