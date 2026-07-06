---
name: fintech-core-build-and-env
description: >-
  Recriar o ambiente de dev do fintech-core DO ZERO até backend + frontend rodando com seed,
  e regenerar o código do contrato OpenAPI. Use para: setup inicial, instalar, ambiente de
  dev, "do zero", primeiro build, mvnw, npm install, docker compose up, Orval,
  generate-sources, api-sync.sh, regenerar cliente, credenciais de seed (carlos@costa.com),
  versões de toolchain (JDK 21, Node 22, npm 11), portas ocupadas, dev-start, claude-tmux.
  NÃO cobre operação do dia a dia, deploy nem sincronização de banco (sync-db.sh,
  sync-tenant.sh) — isso é fintech-core-run-and-operate; nem perfis Spring e env vars —
  isso é fintech-core-config-and-flags.
---

# fintech-core — Build & Ambiente de Dev

Runbook para levantar o ambiente completo do zero (clone → app rodando com dados de seed) e **casa única** do pipeline de regeneração de código do contrato OpenAPI (`api-sync.sh`) e das credenciais de dev/teste.

## Quando NÃO usar

| Situação | Skill irmã |
|---|---|
| Perfis Spring, variáveis de ambiente, environments Angular, eixos de config | `fintech-core-config-and-flags` |
| Rodar/operar no dia a dia, deploy, `sync-tenant.sh`, workflow de migration | `fintech-core-run-and-operate` |
| Erro estranho durante o setup (checksum Flyway, drift do Orval, etc.) | `fintech-core-debugging-playbook` |
| Convenções de teste, `test-summary.sh`, CI, Sonar | `fintech-core-validation-and-qa` |
| Fazer uma mudança de código/schema | `fintech-core-change-control` |

---

## 1. Pré-requisitos (versões reais, verificadas em 2026-07-04)

| Ferramenta | Versão | Fonte da verdade |
|---|---|---|
| JDK | **21** (CI usa distribution `temurin`) | `backend/pom.xml` (`<java.version>21</java.version>`), `.github/workflows/ci-cd.yml` |
| Spring Boot | 4.0.1 (parent do pom — não é 3.x) | `backend/pom.xml` |
| Node.js | **22** (é o que o CI usa; não há campo `engines` no package.json) | `.github/workflows/ci-cd.yml` |
| npm | **11.x** (`"packageManager": "npm@11.6.2"`) | `frontend/package.json` |
| Docker + Docker Compose | qualquer versão recente (postgres:16-alpine + pgadmin4) | `docker-compose.yml` |
| Angular / TypeScript / Vitest / Orval | 21.2.x / ~5.9.2 / 4.x / 8.13.x — instalados pelo `npm install`, não precisa instalar global | `frontend/package.json` |

Maven **não** precisa estar instalado: use sempre o wrapper `./mvnw` (em `backend/`). Angular CLI também não: os scripts npm chamam o `ng` local.

Verificação rápida:

```bash
java -version        # deve reportar 21.x
node -v              # deve reportar v22.x
npm -v               # deve reportar 11.x
docker compose version
```

## 2. Runbook: do zero ao app rodando com seed

Execute a partir da raiz do repo (`~/fintech-core` após o clone).

```bash
# 1. Clone
git clone https://github.com/<owner>/fintech-core.git ~/fintech-core
cd ~/fintech-core

# 2. Banco: PostgreSQL 16 + pgAdmin
docker compose up -d
# Sobe: fintech-postgres (postgres:16-alpine, porta 5432, db=fintech, user=admin, senha=secret)
#       fintech-pgadmin  (porta 5050, login admin@fintech.com / admin)
# Dados persistem em ./.docker/postgres-data

# 3. Backend (porta 8080)
cd backend
./mvnw spring-boot:run
# Perfil ativo default: dev (spring.profiles.active=dev em application.properties).
# No perfil dev o Flyway lê classpath:db/migration + classpath:db/seed e aplica
# V1–V21 intercaladas por número de versão: schema (V1–V9, V11, V12, V14, V15,
# V17, V19, V21) + seeds dev (V13, V16, V18, V20 — dataset "Família Costa").
# Aponta para jdbc:postgresql://localhost:5432/fintech (admin/secret) — bate com o compose.

# 4. Confirme o backend de pé (em outro terminal)
curl http://localhost:8080/actuator/health   # {"status":"UP"}

# 5. Frontend (porta 4200)
cd ~/fintech-core/frontend
npm install
npm start            # = ng serve → http://localhost:4200

# 6. Login com as credenciais do seed (abaixo)
```

O primeiro `./mvnw spring-boot:run` baixa dependências e roda o `generate-sources` automaticamente (ver §4) — a primeira subida demora vários minutos.

### Credenciais de seed (CASA ÚNICA — verificadas nos arquivos de seed em 2026-07-04)

| Contexto | Login | Senha | Fonte |
|---|---|---|---|
| Dev local (banco com seed Flyway) | `carlos@costa.com` | `costa123` | `backend/src/main/resources/db/seed/V13__seed_dev.sql` (BCrypt de "costa123", user ADMIN Carlos Costa) |
| Testes de integração (Testcontainers) | `admin@test.com` | `admin123` | `backend/src/test/resources/sql/seed_base.sql` |

Todos os usuários do seed dev compartilham a senha `costa123`. São credenciais públicas do dataset de testes — não confundir com credenciais reais (Neon/Railway), que ficam fora do git.

## 3. Pipeline do contrato OpenAPI (CASA ÚNICA do api-sync)

Fonte da verdade do contrato: `api-spec/openapi.yaml` (spec-first). Após **qualquer** edição no spec, rode o pipeline completo:

```bash
./scripts/api-sync.sh
```

O que o script faz, exatamente nesta ordem (verificado lendo `scripts/api-sync.sh`):

1. **Copia o spec para o static do backend** — `cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml`. É esse arquivo que o backend serve em `/openapi.yaml` (e que o Swagger UI do springdoc exibe).
2. **Regenera as interfaces Spring** — `(cd backend && ./mvnw -q generate-sources)`. O `openapi-generator-maven-plugin` 7.4.0 (`generatorName=spring`, `interfaceOnly=true`, `generateModels=false`) lê `../api-spec/openapi.yaml` e gera interfaces em `backend/target/generated-sources/openapi` (**não commitado** — os DTOs reais são mapeados via `schemaMappings`/`importMappings` no pom).
3. **Regenera o client Angular** — `(cd frontend && npm run api:generate)` = `orval --config orval.config.ts` (input `../api-spec/openapi.yaml`, output `src/app/core/api` em modo `tags-split`, client `angular`, `provideIn: 'root'`).
4. **Remove `frontend/src/app/core/api/auth/auth.service.ts`** — o Orval o regenera a cada rodada, mas o projeto usa um auth service próprio; o arquivo regenerado é lixo que conflita (gotcha documentado em `summary.md`).

**Se rodar os passos manualmente / fora de ordem:**
- Pular o passo 1 → o `/openapi.yaml` servido pelo backend (e o Swagger UI) fica **desatualizado** em relação ao contrato real. Os geradores não são afetados (ambos leem `api-spec/openapi.yaml` direto), então o drift é silencioso.
- Pular o passo 2 → controllers que implementam as interfaces geradas quebram a compilação no próximo build (interface antiga em `target/`), ou pior: compilam contra o contrato velho.
- Pular o passo 4 → `auth.service.ts` regenerado volta a existir e conflita com o fluxo de auth escrito à mão. É o esquecimento mais comum ao rodar `npm run api:generate` avulso — por isso **use sempre o script**, nunca os passos soltos.
- A ordem 2↔3 entre si é indiferente (fontes independentes), mas não há motivo para desviar do script.

## 4. `./mvnw generate-sources` é pré-requisito de compilação?

**Para builds Maven: não é um passo manual.** O goal `generate` do plugin não declara `<phase>` explícita, então usa o binding default na fase `generate-sources` — qualquer ciclo de vida (`compile`, `test`, `verify`, `spring-boot:run`) o executa automaticamente antes de compilar. Você só roda `./mvnw generate-sources` avulso quando quer **apenas** regenerar as interfaces (é o que o `api-sync.sh` faz).

**Para a IDE: sim, na prática.** As interfaces vivem em `target/generated-sources/openapi` e não são commitadas; num clone limpo a IDE mostra erros de "classe não encontrada" nos controllers até você rodar um primeiro `./mvnw generate-sources` (ou qualquer build) e marcar o diretório como source root.

## 5. Sync de banco (passo opcional do setup inicial)

Se quiser começar com os dados reais da Neon em vez do seed, rode uma primeira sincronização
**após** o runbook do §2: `./scripts/sync-db.sh` (requer `.env` com `DATABASE_URL_NEON` baseado
em `scripts/.env.template`; reinicie o backend depois). Pré-requisitos completos, comportamento
fail-safe e uso no dia a dia de `sync-db.sh` e `sync-tenant.sh`: **casa é
`fintech-core-run-and-operate`** (seção de sincronizações operacionais).

## 6. Scripts auxiliares de ambiente

### `./scripts/dev-start.sh [front|back|both]` (default: `both`)

Sobe backend e/ou frontend em foreground com limpeza prévia de portas:
1. Mata (via `ss` + `kill -9`, sem sudo) qualquer processo nas portas 8080 (back) e 4200 (front) — resolve o clássico "porta ocupada" de sessões anteriores.
2. Inicia `./mvnw spring-boot:run -q` em `backend/` e/ou `npm start` em `frontend/`, em background do próprio script.
3. `trap` em Ctrl+C encerra os processos **e seus filhos** (`pkill -P`) — evita processos Node órfãos segurando a porta 4200.

Não sobe o Docker — `docker compose up -d` continua sendo passo separado.

### `./scripts/claude-tmux.sh [attach]`

Cria (ou re-anexa, com `attach` ou se já existir) a sessão tmux `claude-dev` com 3 panes:
- Esquerda (70%): Claude Code na raiz do repo.
- Direita superior: `watch -n2 'git log --oneline -15'` — commits chegando ao vivo.
- Direita inferior: `npx tsc --noEmit --watch` em `frontend/` — erros de tipo em tempo real.

## 7. Armadilhas de setup (verificadas)

| Sintoma | Causa / correção |
|---|---|
| Backend não sobe: erro de validação de schema do Hibernate | `spring.jpa.hibernate.ddl-auto=validate` (global, `application.properties`): o Hibernate **exige** que o schema já exista — quem cria é o Flyway na subida. Se as migrations não rodaram (banco errado, Flyway desabilitado), a validação falha. Nunca mudar para `update` (regra inviolável do CLAUDE.md). |
| Porta 5432 ocupada | Postgres nativo da máquina conflita com o container. Pare o serviço local ou mude o mapeamento no `docker-compose.yml`. |
| Porta 8080 ou 4200 ocupada | Sessão anterior órfã. `./scripts/dev-start.sh` mata os processos automaticamente antes de subir. |
| Backend conecta na Neon em vez do banco local | Mecanismo de fallback: com `neon.enabled=true` **e** `application-local.properties` presente no classpath, o `NeonFallbackEnvironmentPostProcessor` sobrescreve o datasource com as credenciais da Neon no startup. Default é `neon.enabled=false` (`application-dev.properties`) = banco local. O `application-local.properties` contém credenciais reais — nunca commitar seu conteúdo em docs. |
| IDE cheia de erros "classe não encontrada" em controllers num clone limpo | Interfaces geradas ainda não existem em `target/` — ver §4. |
| Erro ao subir Swagger/springdoc após mexer em dependências | `springdoc-openapi-starter-webmvc-ui` está **pinado em 2.8.9** no pom — 2.6.0 é incompatível com Spring Boot 4.0.1 (gotcha documentado em `summary.md`/`tech.md`). Não fazer downgrade. |
| Backend não inicia após editar um seed já aplicado | Checksum Flyway — seeds V13/V16 aplicados são imutáveis; correção via nova versão (ex.: V18). Sintoma e triagem completa em `fintech-core-debugging-playbook`. |
| `npm test` / specs quebrando fora do builder Angular | Rodar via `npm test` (= `ng test`), nunca `npx vitest` cru — detalhes em `fintech-core-debugging-playbook`. |

## Proveniência e manutenção

Fatos verificados em **2026-07-04** (skill revisada em 2026-07-05 — §5 reduzido: casa do sync é `fintech-core-run-and-operate`) contra: `backend/pom.xml`, `frontend/package.json`, `frontend/orval.config.ts`, `docker-compose.yml`, `.github/workflows/ci-cd.yml`, `backend/src/main/resources/application.properties` + `application-dev.properties`, `backend/src/main/resources/db/{migration,seed}/`, `backend/src/test/resources/sql/seed_base.sql`, `scripts/{api-sync,sync-db,dev-start,claude-tmux}.sh`, `scripts/.env.template`.

Re-verificação de uma linha por fato volátil:

```bash
grep java.version backend/pom.xml                          # JDK
grep -E 'version>4\.' backend/pom.xml | head -1            # Spring Boot
grep packageManager frontend/package.json                  # npm
grep node-version .github/workflows/ci-cd.yml              # Node do CI
grep image: docker-compose.yml                             # postgres:16-alpine
ls backend/src/main/resources/db/seed/                     # seeds dev (V13/V16/V18/V20)
grep -n carlos@costa backend/src/main/resources/db/seed/V13__seed_dev.sql   # credencial dev
cat scripts/api-sync.sh                                    # pipeline do contrato
grep ddl-auto backend/src/main/resources/application.properties             # validate
grep springdoc -A2 backend/pom.xml | grep version          # 2.8.9 pinado
```
