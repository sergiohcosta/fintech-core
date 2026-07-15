# Fase 2 — Cluster B: segurança

> Campanha de saneamento (auditoria 2026-07). Escopo: **#143, #144**. Ordem obrigatória:
> vem depois da Fase 1 (dinheiro), antes das demais. Skill: `fintech-core-bug-backlog-campaign`.

## #143 — CSV formula injection no export de fatura

**Causa-raiz:** `frontend/src/app/core/csv.utils.ts` → `csvField` escapa apenas `;`, `"`, `\n`
(estrutura do CSV, RFC 4180). Um campo cujo texto começa com `=`, `+`, `-`, `@`, TAB ou CR é
interpretado como **fórmula** por Excel/LibreOffice ao abrir o arquivo — ex.: uma transação com
descrição `=HYPERLINK("http://malicioso","clique")` ou `+cmd|...` executa no cliente. O export de
fatura (`invoice-detail.utils.ts`) manda `description`/`categoryPath` crus.

**Reprodução:** estender `csv.utils.spec.ts` com payloads `=HYPERLINK(...)`, `+1+1`, `-2+3`,
`@SUM(A1)`, `\tcmd`, e um campo com `\r`. Rodar via `./scripts/test-summary.sh frontend`
(ou `ng test`, nunca `npx vitest` cru). Hoje saem crus.

**Solução (OWASP CSV injection):** em `csvField`, se o valor começa com `= + - @ \t \r` →
prefixar `'` (apóstrofo). Incluir `\r` na condição de quoting junto de `\n`. **Derivação:** o
apóstrofo força a planilha a tratar a célula como texto; quoting (aspas) protege a ESTRUTURA do
CSV mas NÃO impede a execução da fórmula — são defesas distintas, ambas necessárias.

**Cerca:** NÃO remover caracteres do dado (descrição "−R$ 50 ajuste" é legítima) — neutralizar
só na borda de export.

## #144 — bypass do rate limit via X-Forwarded-For + DoS de memória

**Causa-raiz (dois problemas independentes):**
1. `AuthController.login` monta a chave `ip:email` lendo `X-Forwarded-For` **do cliente**, sem
   trusted proxy. O atacante rotaciona o header a cada request → chave nova toda vez → nunca
   atinge o teto de 5 falhas por email. O contrato declarado (`summary.md`) é "5 falhas por
   email/minuto" — o IP na chave só o enfraquece quando o header é controlável.
2. `LoginRateLimiter` só tem eviction **preguiçosa** (na releitura da chave ou no sucesso). Um
   flood de emails aleatórios cresce o `ConcurrentHashMap` sem teto → OOM.

**Reprodução:**
- Controller (MockMvc): 6+ POSTs `/auth/login` com senha errada, mesmo email, variando
  `X-Forwarded-For` a cada request → hoje NUNCA retorna 429.
- Limiter (`LoginRateLimiterTest`, estender): registrar N chaves distintas e verificar que o
  mapa não cresce sem limite (sweep remove expiradas; teto respeitado).

**Solução:**
1. **Chave por email apenas** — `AuthController.login` deixa de ler `X-Forwarded-For`; chave =
   `data.email()` (o `normalize()` do limiter já faz `toLowerCase`). Remove os imports de
   `HttpServletRequest`/`RequestContextHolder`/`ServletRequestAttributes`. *(forward-headers-strategy
   fica fora do escopo: confiar no XFF exige trusted proxy configurado — sem isso é teatro, e o
   fix por email não precisa de IP.)*
2. **Eviction com teto (decisão do dev: `@Scheduled` sweep + cap, sem dependência nova):**
   - `@Scheduled(fixedDelayString="${security.rate-limit.sweep-ms:60000}")` varre e remove
     janelas expiradas (`now > startedAt + window`). Exige `@EnableScheduling` (adicionar se
     ainda não houver).
   - Teto rígido: `@Value security.rate-limit.max-keys:100000`. Em `registerFailure`, se
     `size >= maxKeys` e a chave é nova → sweep inline de expiradas; se ainda `>= maxKeys` →
     **skip** (não rastreia a nova chave). Fail-open é aceitável: rate-limit é defesa-em-
     profundidade best-effort, a checagem de credencial (BCrypt) continua; atacantes já
     rastreados seguem bloqueados. O teto garante memória O(maxKeys).

**Cerca:** NÃO logar senha nem token nos testes/logs do limiter (o WARN atual loga só o email).

## Critério de pronto (mensurável)

- Payloads de fórmula saem prefixados com `'`; `\r` força quoting. `csv.utils.spec.ts` verde.
- 6ª tentativa falha por email → 429 **independente** de `X-Forwarded-For`.
- Mapa do limiter com teto/limpeza comprovados por teste (sweep + cap).
- `./scripts/test-summary.sh` verde (backend + frontend).

## Dataset

Sem mudança de schema/seed. `dataset.md` não exige atualização.
