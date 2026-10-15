# Design: Service Worker (Fase 3 — mobile-friendly)

**Data:** 2026-10-14
**Escopo:** Frontend only — `ng add @angular/pwa` + `ngsw-config.json` customizado
**Fase do roadmap:** Slice 2 de 7 (continua jornada mobile-friendly)

---

## Contexto e motivação

O app abre lento no 4G fraco e fica inutilizável sem rede. Em mobile, "abriu rápido" e "funciona no metrô sem sinal" são critérios de UX básicos — sem service worker, o Angular entrega o shell HTML em todo cold start (full network round-trip), e qualquer GET de API fica bloqueado se a rede cair.

Esse design entrega o **acesso offline-leitura** e o **shell pré-cacheado** sem entrar em fila de mutações offline (decisão de escopo — Fase 7 dedicada).

---

## Decisões já tomadas (resumo do grill pré-execução)

| # | Decisão | Origem |
|---|---|---|
| SW | `ng add @angular/pwa` (Workbox por baixo) | Q17 do grill |
| Estratégia | Shell **cache-first** + `dataGroups` `performance` (stale-while-revalidate) para `/api/**` | Q8 do grill |
| Fila de escritas offline | **NÃO** nesta fase (Fase 7 dedicada) | Q8 do grill |
| PWA instalável (Add to Home Screen) | **NÃO** — `manifest` é gerado mas sem prompt | Q4 do grill |
| HTTPS | Prod (Netlify) já tem; dev usa `localhost` que é exempted pelo SW spec | Q17 do grill |

---

## Mudanças concretas

### 1. Adicionar PWA via CLI

```bash
cd frontend
ng add @angular/pwa --skip-confirmation
```

Gera: `ngsw-config.json`, `manifest.webmanifest` (em `public/`), atualiza `angular.json` (`serviceWorker: true` no build config), atualiza `app.config.ts` com `provideServiceWorker('@angular/service-worker', { enabled: !isDevMode(), registrationStrategy: 'registerWhenStable:95SW' })`, adiciona `@angular/service-worker` em `dependencies`.

### 2. Customizar `ngsw-config.json`

```jsonc
{
  "$schema": "./node_modules/@angular/service-worker/config/schema.json",
  "index": "/index.html",
  // assetGroups defaults do ng add (assets + files) ficam OK — cobrem imagens/fonts/icons.
  "dataGroups": [
    {
      "name": "api-reads",
      "urls": ["/api/**"],
      "cacheConfig": {
        "strategy": "performance",  // = stale-while-revalidate
        "maxSize": 200,
        "maxAge": "1d",
        "timeout": "5s"
      }
    }
  ],
  "navigationUrls": [
    "/**",
    "!/**/*.*",  // ignora arquivos estáticos (já em assetGroups)
    "!/**/*__*",  // ignora rotas Angular underscore-prefix
    "!/**/* */**"  // ignora rotas com espaço
  ]
}
```

`strategy: "performance"` serve do cache imediatamente (offline-first) e atualiza em background. Bom para UX mobile onde latência percebida > freshness.

`maxAge: "1d"` evita servir dado muito velho sem rede.

`maxSize: 200` cobre ~200 GETs cacheados (suficiente para navegação típica).

### 3. Customizar `manifest.webmanifest`

- `name`: "Fintech Core"
- `short_name`: "Fintech"
- `theme_color`: `#00b8d4` (cyan primary do `mat.theme` atual)
- `background_color`: `#fafafa` (surface light)
- `display`: `"standalone"`
- `start_url`: `"/dashboard"`
- `scope`: `"/"`
- Ícones: usar os que existem em `public/` (favicon). Se não houver ícones PWA (192x192, 512x512), deixar o que o `ng add` gerar.

### 4. Validar `app.config.ts`

Confirmar que `provideServiceWorker` está registrado **somente em prod** (default do `ng add` faz isso via `!isDevMode()`). Não modificar.

---

## Arquivos a criar/alterar

| Arquivo | Tipo | Mudança |
|---|---|---|
| `frontend/ngsw-config.json` | novo (via CLI) | Customizar `dataGroups` + `navigationUrls` |
| `frontend/src/manifest.webmanifest` | novo (via CLI) | Customizar name/short_name/theme/background/start_url |
| `frontend/src/app/app.config.ts` | edit (via CLI) | `provideServiceWorker('@angular/service-worker', ...)` |
| `frontend/angular.json` | edit (via CLI) | `serviceWorker: true` no build config |
| `frontend/package.json` | edit (via CLI) | Adiciona `@angular/service-worker` |

Total: 1 edit effective do usuário (customizar JSONs gerados). O resto é trabalho do CLI.

---

## Critérios de aceite

### Funcionais

- [ ] `ng build` em prod (`NODE_ENV=production`) gera `ngsw.json` no output
- [ ] `ngsw-worker.js` aparece nos chunks do build (lazy chunk separado, ~30kB)
- [ ] Em prod build, `index.html` referencia `ngsw-worker.js` (via `ngsw.json` registrado automaticamente)
- [ ] `provideServiceWorker` está em `app.config.ts` e condicional a `!isDevMode()`
- [ ] `manifest.webmanifest` é válido (lint em https://manifest-validator.appspot.com/ — referência, não teste automatizado)
- [ ] HTTP `GET /api/transactions` em prod build serve do cache quando offline (validar em DevTools → Application → Service Workers → Offline)

### Não-funcionais

- [ ] Build verde (`npm run build`)
- [ ] 365+ testes Vitest verde
- [ ] Sem novos warnings de bundle budget (SW é lazy chunk separado, fora do initial bundle)
- [ ] Sem `<any>` TypeScript (o `ng add` não introduz TS, mas validar que customizações também não)
- [ ] Sem `Co-Authored-By` no commit
- [ ] Sem `console.log`

### Validação manual (após deploy em hmg ou prod)

- [ ] Chrome DevTools → Application → Service Workers: SW registrado, status "activated and is running"
- [ ] Em DevTools, marcar "Offline" e recarregar `/dashboard` — shell aparece, GET de dashboard serve do cache
- [ ] Em mobile real (Android via ADB ou iOS), abrir o app, colocar em airplane mode, recarregar — shell + última visualização aparecem

---

## Riscos abertos

1. **Cache stale-serve**: dados de transações podem ficar até 1 dia desatualizados se o usuário ficar offline. Mitigação: `maxAge: "1d"` é o limite superior; em rede normal, SWR atualiza em background e o usuário sempre vê dado fresco.
2. **Quebra de contrato se endpoint mudar**: SW cacheia por URL. Se `/api/transactions` mudar formato, cache antigo serve por até 1d. Mitigação: bump de `dataGroups.maxAge` ou usar `versionamento de API` (path `/v2/` no spec).
3. **HTTPS no dev**: `ng serve` por default usa HTTP, mas `localhost` é exempted pelo SW spec — funciona em dev. Em prod (Netlify) já é HTTPS.
4. **PWA instalável**: o manifest é gerado mas NÃO habilitamos "Add to Home Screen" prompt nesta fase (decisão Q4). O manifest sozinho não dispara prompt.
5. **Fila de escritas**: usuário mobile sem rede que tenta lançar transação verá erro de rede. Mitigação: Fase 7 (IndexedDB + retry) cobre. Por agora, snackbar de erro de rede é aceitável.

---

## Out of scope (próximas fases)

| Fase | Tema | Relação |
|---|---|---|
| **4** | Lista de transações mobile (`<mat-list>` + swipe actions) | Pré-requisito: SW (offline-leitura) já existe |
| **5** | Lançar transação como `MatBottomSheet` | Pré-requisito: SW; integra com Fase 7 (fila offline) |
| **7** | Storage + biometria + fila de escritas offline | Naturalmente casa com SW (retry com mutações) |

---

## Notas de implementação pro @fixer

- `ng add @angular/pwa` é interativo. Use `--skip-confirmation` para evitar prompts.
- O CLI já aplica quase tudo. Seu trabalho principal é customizar `ngsw-config.json` (acima) e `manifest.webmanifest` (acima) **após** o CLI rodar.
- NÃO alterar `app.config.ts` além do que o CLI gera — o default `!isDevMode()` está correto.
- NÃO modificar `angular.json` além do que o CLI aplica.
- Worktree: `/home/sergio/projetos/fintech-core/.worktrees/sw-mobile-friendly`. Use `git -C ...` em todos os comandos git.
- 1 commit na `feature/sw-mobile-friendly` com mensagem: `feat(frontend): adiciona service worker com shell cache e api stale-while-revalidate`.