// scripts/a11y-crawl.mjs
// Headless Chrome a11y + layout crawl across all Angular routes.
// Emits a11y-baseline.json with per-route violations and clustered root causes.
// Usage:
//   node scripts/a11y-crawl.mjs [--base-url=http://localhost:4200]
import fs from 'node:fs';
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';

const BASE_URL = (() => {
  const arg = process.argv.find((a) => a.startsWith('--base-url='));
  return arg ? arg.slice('--base-url='.length) : (process.env.BASE_URL || 'http://localhost:4200');
})();

const CLS_LIMIT = 0.1;

// Routes that don't depend on a guarded shell. Others expect auth_token injected.
// Sample ids are placeholders; backend 404s collapse to the empty-state markup.
const ROUTES = [
  '/login',
  '/register',
  '/accept-invite?token=demo',
  '/forgot-password',
  '/reset-password?token=demo',
  '/dashboard',
  '/accounts',
  '/accounts/new',
  '/accounts/1',
  '/categories',
  '/categories/new',
  '/categories/taxonomy',
  '/categories/1',
  '/transactions',
  '/transactions/timeline',
  '/transactions/new',
  '/transactions/1',
  '/team',
  '/invoices',
  '/invoices/inv-1',
  '/planning/current',
  '/planning/cycles',
  '/planning/cycles/1',
  '/planning/recurring',
  '/planning/settings',
  '/recurrences',
  '/import',
  '/import/1',
  '/imports',
];

// JWT fabricator: header alg=none, payload with future exp, empty signature.
// authGuard only checks exp via jwt-decode (auth-guard.ts:12 → AuthService.isAuthenticated).
// shortcut: signature is not verified by the client guard; if backend rejects, switch to HS256 with secret ''.
function fabricateToken() {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const header = enc({ alg: 'none', typ: 'JWT' });
  const exp = Math.floor(Date.now() / 1000) + 60 * 60 * 24 * 30;
  const payload = enc({
    sub: 'carlos@costa.com',
    name: 'Carlos',
    tenant_id: 't1',
    role: 'ADMIN',
    exp,
  });
  return `${header}.${payload}.`;
}

const AUTH_TOKEN = process.env.AUTH_TOKEN || fabricateToken();

// Walk up from el until we hit an Angular component host (`_nghost-*`).
// Falls back to tag name for plain elements (no host bindings).
function nearestAngularHost(el) {
  let cur = el;
  while (cur && cur.nodeType === 1) {
    if (cur.attributes) {
      for (const a of cur.attributes) {
        if (a.name.startsWith('_nghost-')) return cur.tagName.toLowerCase();
      }
    }
    cur = cur.parentElement;
  }
  return el?.tagName?.toLowerCase() || 'unknown';
}

async function crawlRoute(context, route) {
  const page = await context.newPage();
  let httpStatus = null;
  let redirectedTo = null;
  page.on('response', (res) => {
    if (res.url() === `${BASE_URL}${route}` || res.url().endsWith(route.split('?')[0])) {
      httpStatus = res.status();
    }
  });
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame() && f.url() !== `${BASE_URL}${route}`) {
      redirectedTo = new URL(f.url()).pathname + new URL(f.url()).search;
    }
  });

  await page.addInitScript((token) => {
    try { localStorage.setItem('auth_token', token); } catch {}
  }, AUTH_TOKEN);

  try {
    await page.goto(`${BASE_URL}${route}`, { waitUntil: 'networkidle', timeout: 30_000 });
    await page.waitForTimeout(500);
    await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  } catch (err) {
    await page.close();
    return { route, error: err.message, violations: [], cls: 0, httpStatus, redirectedTo };
  }

  // O redirect pos-auth (de /login para /dashboard quando ha token) pode destruir
  // o contexto de pagina entre o goto e o evaluate. Envolve tudo num try/catch
  // para que a falha de uma rota nao derrube o crawler inteiro.
  let cls = 0, axe = { violations: [] }, scan = { issues: [], componentMap: {} };
  try {
    cls = await page.evaluate(() => {
      return new Promise((resolve) => {
        let total = 0;
        try {
          const po = new PerformanceObserver((list) => {
            for (const e of list.getEntries()) if (!e.hadRecentInput) total += e.value;
          });
          po.observe({ type: 'layout-shift', buffered: true });
        } catch {}
        setTimeout(() => resolve(total), 800);
      });
    });

    axe = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();

    scan = await page.evaluate((clsLimit) => {
    function selectorFor(el) {
      if (el.id) return `#${el.id}`;
      const tag = el.tagName.toLowerCase();
      const cls = el.className && typeof el.className === 'string'
        ? '.' + el.className.trim().split(/\s+/).filter(Boolean).join('.')
        : '';
      return `${tag}${cls}`;
    }

    function nearestHost(el) {
      let cur = el;
      while (cur && cur.nodeType === 1) {
        if (cur.attributes) {
          for (const a of cur.attributes) if (a.name.startsWith('_nghost-')) return cur.tagName.toLowerCase();
        }
        cur = cur.parentElement;
      }
      return el?.tagName?.toLowerCase() || 'unknown';
    }

    const issues = [];
    const componentMap = {};

    const interactives = [...document.querySelectorAll(
      'a[href],button,input,select,textarea,[tabindex]:not([tabindex="-1"])'
    )].filter((el) => el.offsetParent !== null);

    for (const el of interactives) {
      const r = el.getBoundingClientRect();
      const sel = selectorFor(el);
      componentMap[sel] = nearestHost(el);
      if (r.width === 0 || r.height === 0) {
        issues.push({ ruleId: 'layout-zero-size', impact: 'serious', selector: sel, detail: 'zero-size interactive' });
        continue;
      }
      if (r.right > window.innerWidth + 1 || r.left < -1) {
        issues.push({ ruleId: 'layout-offscreen', impact: 'serious', selector: sel, detail: `left=${Math.round(r.left)},right=${Math.round(r.right)}` });
      }
      const cx = Math.min(Math.max(r.left + r.width / 2, 1), window.innerWidth - 1);
      const cy = Math.min(Math.max(r.top + r.height / 2, 1), window.innerHeight - 1);
      if (cy > 0 && cy < window.innerHeight) {
        const hit = document.elementFromPoint(cx, cy);
        if (hit && !el.contains(hit) && !hit.contains(el)) {
          issues.push({ ruleId: 'layout-overlap', impact: 'serious', selector: sel, detail: `covered by ${hit.tagName.toLowerCase()}` });
        }
      }
    }

    // Pre-resolve host for axe violations' selectors while page is alive.
    const axeNodes = [...document.querySelectorAll('*')];
    for (const el of axeNodes) {
      if (!el.id) continue;
      const sel = `#${el.id}`;
      if (!componentMap[sel]) componentMap[sel] = nearestHost(el);
    }

    return { issues, componentMap };
  }, CLS_LIMIT);
  } catch (err) {
    // Falha pos-evaluate (redirect mid-flight). cls/axe/scan ficam com defaults.
  } finally {
    await page.close().catch(() => {});
  }

  function componentFor(selector) {
    // Try direct lookup first.
    if (scan.componentMap[selector]) return scan.componentMap[selector];
    // Try the first class in the selector (e.g. ".foo.bar" -> ".foo").
    const firstClass = selector.match(/\.([\w-]+)/);
    if (firstClass) {
      const sel = '.' + firstClass[1];
      if (scan.componentMap[sel]) return scan.componentMap[sel];
    }
    return 'unknown';
  }

  const violations = [
    ...axe.violations.flatMap((v) =>
      v.nodes.map((n) => {
        const selector = n.target.join(' ');
        return {
          ruleId: v.id,
          impact: v.impact,
          component: componentFor(selector),
          selector,
          detail: n.failureSummary?.split('\n')[0] || v.help,
        };
      })
    ),
    ...scan.issues.map((c) => ({ ...c, component: componentFor(c.selector) })),
    ...(cls > CLS_LIMIT
      ? [{ ruleId: 'layout-cls', impact: 'moderate', component: 'page-root', selector: 'body', detail: `CLS=${cls.toFixed(3)}` }]
      : []),
  ];

  return { route, violations, cls, httpStatus, redirectedTo };
}

// Wait until ng serve is up.
async function waitForServer(url, timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url);
      if (res.ok) return true;
    } catch {}
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

async function main() {
  if (!(await waitForServer(BASE_URL, 5000))) {
    console.error(`Server not reachable at ${BASE_URL}. Start ng serve first.`);
    process.exit(2);
  }

  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
  });

  const routeResults = {};
  const clusterMap = new Map();

  for (const route of ROUTES) {
    process.stdout.write(`→ ${route} ... `);
    const result = await crawlRoute(context, route);
    routeResults[route] = result;
    for (const v of result.violations) {
      const key = `${v.component}::${v.ruleId}`;
      let c = clusterMap.get(key);
      if (!c) {
        c = { key, component: v.component, rule: v.ruleId, impact: v.impact, routes: new Set(), selectors: new Set(), samples: [] };
        clusterMap.set(key, c);
      }
      c.routes.add(route);
      if (c.selectors.size < 3) c.selectors.add(v.selector);
      if (c.samples.length < 3) c.samples.push({ route, selector: v.selector, detail: v.detail });
    }
    process.stdout.write(`${result.violations.length} violations\n`);
  }

  await browser.close();

  const clusters = [...clusterMap.values()]
    .map((c) => ({
      key: c.key,
      component: c.component,
      rule: c.rule,
      impact: c.impact,
      routeCount: c.routes.size,
      sampleSelectors: [...c.selectors],
      samples: c.samples,
    }))
    .sort((a, b) => b.routeCount - a.routeCount || a.key.localeCompare(b.key));

  const totals = {
    routes: ROUTES.length,
    totalViolations: Object.values(routeResults).reduce((s, r) => s + r.violations.length, 0),
    clusterCount: clusters.length,
    routesWithViolations: Object.values(routeResults).filter((r) => r.violations.length > 0).length,
  };

  const report = {
    baseUrl: BASE_URL,
    timestamp: new Date().toISOString(),
    routeResults,
    clusters,
    totals,
  };

  fs.writeFileSync('a11y-baseline.json', JSON.stringify(report, null, 2));

  console.log('\n=== Cluster summary (top 20 by routeCount) ===');
  console.table(
    clusters.slice(0, 20).map((c) => ({
      key: c.key,
      component: c.component,
      rule: c.rule,
      impact: c.impact,
      routeCount: c.routeCount,
    }))
  );
  console.log(`\nTotals: ${JSON.stringify(totals)}`);
  console.log('Wrote a11y-baseline.json');
  process.exit(totals.totalViolations > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error(err);
  process.exit(2);
});