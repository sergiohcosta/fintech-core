---
name: fintech-core-run-and-operate
description: >-
  Rodar e operar o fintech-core no dia a dia — subir/start do sistema (docker compose,
  spring-boot:run, npm start), health check, Swagger UI, pgAdmin, migration Flyway em
  operação (flyway_schema_history), deploy automático (push em main → Railway backend +
  Netlify frontend), banco Neon, sync de banco (sync-db.sh Neon→local, sync-tenant.sh
  pull/push — casa única dos comandos de sync), logs MDC (requestId, X-Request-ID), hook
  post-merge do SonarQube. Use quando a tarefa for rodar, subir, operar, deployar,
  sincronizar banco ou ler logs. NÃO cobre montar o ambiente do zero nem toolchain —
  isso é fintech-core-build-and-env.
---

# fintech-core — Rodar e Operar

Runbook para operar o sistema localmente e entender o pipeline de deploy **até onde este repositório o define**. Tudo abaixo foi verificado contra o repo em 2026-07-04; o que não é verificável aqui está rotulado "não verificado".

## Quando NÃO usar

| Necessidade | Skill irmã |
|---|---|
| Montar o ambiente do zero (JDK/Node/Docker, seeds, credenciais de dev, pipeline Orval) | `fintech-core-build-and-env` |
| Catálogo de perfis Spring, properties, env vars, environments Angular | `fintech-core-config-and-flags` |
| Testes, gates de CI em detalhe, SonarQube (scan, gate, cobertura) | `fintech-core-validation-and-qa` |
| Criar/nomear migration, regra de imutabilidade, ciclo SDD | `fintech-core-change-control` |
| Sintoma estranho no startup ou em operação (checksum Flyway etc.) | `fintech-core-debugging-playbook` |

---

## 1. Subir o sistema localmente

Ordem: banco → backend → frontend. O cwd do shell **não persiste** entre comandos de agente — use paths absolutos ou `-f`/`-C`.

### 1.1 Banco (Docker)

```bash
docker compose up -d          # da raiz do repo
```

| Serviço | Container | Porta | Acesso |
|---|---|---|---|
| PostgreSQL 16 (`postgres:16-alpine`) | `fintech-postgres` | 5432 | db `fintech`, user `admin`, senha `secret` |
| pgAdmin (opcional, debug visual) | `fintech-pgadmin` | http://localhost:5050 | `admin@fintech.com` / `admin` |

Dados persistem em `./.docker/postgres-data`.

### 1.2 Backend (Spring Boot, porta 8080)

```bash
cd /home/sergio/fintech-core/backend && ./mvnw spring-boot:run
# ou, sem depender do cwd:
/home/sergio/fintech-core/backend/mvnw -f /home/sergio/fintech-core/backend/pom.xml spring-boot:run
```

- Perfil default: `dev` (`spring.profiles.active=dev` em `application.properties`). No perfil dev os seeds rodam junto das migrations (`spring.flyway.locations=classpath:db/migration,classpath:db/seed`) e o DevTools reinicia automaticamente ao recompilar.
- Nunca prefixe paths já relativos (gera `backend/backend/...` — erro recorrente auditado).
- Existe o atalho `./scripts/dev-start.sh [front|back|both]` para subir os dois lados de uma vez.

### 1.3 Frontend (Angular, porta 4200)

```bash
cd /home/sergio/fintech-core/frontend && npm start
```

### 1.4 Verificar que está de pé

```bash
curl http://localhost:8080/actuator/health     # esperado: {"status":"UP"}
```

- **Swagger UI:** http://localhost:8080/swagger-ui.html — configurado em `application.properties` (`springdoc.swagger-ui.path=/swagger-ui.html`) e servindo a spec estática `springdoc.swagger-ui.url=/openapi.yaml` (cópia do contrato em `backend/src/main/resources/static/openapi.yaml`). Se a UI mostrar contrato desatualizado, a cópia estática está atrás de `api-spec/openapi.yaml` — rode `./scripts/api-sync.sh` (pipeline em `fintech-core-build-and-env`).
- Ambos `/swagger-ui/**` e `/openapi.yaml` são rotas públicas (sem JWT).

---

## 2. Migration em operação (Flyway)

O Flyway roda **no startup do backend** — não há comando de migrate manual no fluxo normal. `spring.jpa.hibernate.ddl-auto=validate`: o Hibernate só valida o schema; qualquer divergência entre entidades e banco derruba o startup.

**O que observar no log de startup:**
- Linhas do Flyway informando a versão corrente do schema e migrations aplicadas ("Migrating schema ... to version X").
- Falha típica: `FlywayValidateException` / checksum mismatch → alguém editou uma migration já aplicada (triagem em `fintech-core-debugging-playbook`; regra de imutabilidade e como criar migration nova em `fintech-core-change-control`).
- No perfil dev, os seeds `V13`/`V16` etc. aparecem intercalados nas migrations — é esperado.

**Confirmar a versão aplicada direto no banco:**

```bash
docker exec -it fintech-postgres psql -U admin -d fintech -c \
  "SELECT installed_rank, version, description, success, installed_on
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

`success = f` em qualquer linha → o startup vai falhar até resolver (nunca editando a migration aplicada; sempre via nova versão). Em 2026-07-04 o schema vai de V1 a V21 (detalhe em `database-schema.md`).

---

## 3. Deploy (push em main → auto-deploy)

Não existe job de deploy no CI. O `.github/workflows/ci-cd.yml` só roda testes; o deploy é feito pelas integrações GitHub dos provedores, e o portão de qualidade é o **branch protection** da `main` exigindo os jobs `test-backend` e `test-frontend` verdes (comentário explícito no fim do workflow). Fluxo de merge (`feature → develop → PR → main`) em `fintech-core-change-control`.

| Peça | Provedor | Definido no repo? |
|---|---|---|
| Backend (perfil `prod`) | Railway, auto-deploy no push em `main` | Parcial — não há `railway.json`/`Procfile`; build/start são configurados no painel do Railway (**não verificado no repo**) |
| Frontend | Netlify, auto-deploy no push em `main` | Sim — `frontend/netlify.toml`: base `frontend`, `npm ci && npm run build -- --configuration=production`, publish `dist/frontend/browser`, redirect SPA `/* → /index.html` |
| Banco de produção | Neon (Postgres serverless) | Indícios no repo: `application-prod.properties` comenta "Banco (Neon)" e `sync-db.sh` trata a Neon como fonte da verdade; a URL real vem da env var `DATABASE_URL` (**valor não verificável no repo**) |

**Env vars que o perfil `prod` exige** (lidas em `application-prod.properties`): `PORT`, `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`. Em prod o log vira JSON (`logging.structured.format.console=logstash`) e `show-sql=false`.

**Atenção a comentários defasados:** `application-prod.properties` diz que as vars são "injetadas pelo Render", mas o comentário do CI (mais recente) e o `.env.template` apontam Railway. Trate "Render" como resquício histórico; o provedor atual documentado no repo é Railway.

**Topologia de bancos (o que o repo permite afirmar):**
- **Neon** = banco remoto tratado como fonte da verdade pelos scripts de sync; tem branch `develop` (ver `neon-develop-reset.sh`).
- **Railway** também tem um Postgres próprio — `sync-tenant.sh` usa `DATABASE_URL_RAILWAY` (a `DATABASE_PUBLIC_URL` do painel), ou seja, o ambiente do Railway fala com um banco no próprio Railway, distinto da Neon.
- Qual banco a instância de produção do backend aponta em runtime não é verificável no repo (é a env var `DATABASE_URL` do painel) — **não verificado**.

---

## 4. Sincronizações operacionais de banco

Todos exigem `.env` (ou `.env.local`) na raiz baseado em `scripts/.env.template`. **Nunca** copie credenciais reais para docs/skills. **Sempre reinicie o backend local após qualquer sync** — Hibernate/Flyway não releem o banco a quente.

### 4.1 `./scripts/sync-db.sh` — Neon → local (banco inteiro)

Use quando quiser espelhar o estado completo da Neon no Docker local.

- Requer: container `fintech-postgres` de pé, `pg_dump` instalado na máquina, `DATABASE_URL_NEON` no `.env`.
- **Fail-safe:** o dump da Neon é baixado para arquivo temporário **antes** de tocar o banco local; falha de conexão não destrói nada.
- **Destrutivo no local:** faz `DROP SCHEMA public CASCADE` e restaura por cima. Todo dado local é perdido.

### 4.2 `./scripts/sync-tenant.sh pull|push` — UM tenant, local ↔ Railway

Use para mover só o tenant-alvo, num sentido por execução (`pull` = Railway→local; `push` = local→Railway).

- Requer: `SYNC_TENANT_ID` (UUID do tenant, obrigatório) e `DATABASE_URL_RAILWAY` (URL pública, formato libpq — não jdbc) no `.env`.
- Pede confirmação interativa (`yes`) mostrando o sentido antes de agir.
- **Substitui, não faz merge:** apaga o tenant no destino e recarrega numa única transação (`session_replication_role=replica` desliga FKs durante a carga). Demais tenants do destino não são tocados.
- Premissa: schema idêntico nos dois lados (mesmas migrations).
- ⚠️ **Defasagem conhecida (2026-07-04):** o `LOAD_ORDER` do script ainda inclui `recurring_budget_items` (tabela removida na V21) e o cabeçalho cita "V1–V17". Em bancos já na V21 o `\copy` dessa tabela falha com `ON_ERROR_STOP` — o script precisa de atualização antes de usar (roteie a correção via `fintech-core-change-control`).

### 4.3 `./scripts/neon-develop-reset.sh` — reset da branch develop na Neon

Script de uma linha: `neon branches reset develop --parent`. Restaura a branch `develop` do Postgres da Neon ao estado da branch pai. Requer a CLI da Neon (`neon`) instalada e autenticada — a CLI em si não é provisionada pelo repo (**não verificado**). Use para descartar experimentos feitos no banco develop remoto.

| Preciso de... | Script |
|---|---|
| Espelhar produção/Neon inteira no meu Docker | `sync-db.sh` |
| Levar/trazer só um tenant entre local e Railway | `sync-tenant.sh push` / `pull` |
| Zerar a branch develop do banco Neon | `neon-develop-reset.sh` |

---

## 5. Observabilidade em operação

Logging estruturado com MDC (detalhe da arquitetura em `summary.md`, seção Logging):

| Chave MDC | Quando entra | Origem |
|---|---|---|
| `requestId` | toda requisição | `RequestIdFilter` — reusa o header `X-Request-ID` se o cliente mandar, senão gera UUID; sempre ecoa `X-Request-ID` na resposta |
| `userId` / `tenantId` | após validar o JWT | `SecurityFilter` |

- **Dev:** console legível — `application-dev.properties` define pattern com `[%X{requestId}][%X{userId}]` visíveis. Os logs aparecem no terminal do `spring-boot:run`.
- **Prod:** JSON logstash no stdout (`application-prod.properties`), parseável por agregadores; no Railway, aterrissa no log viewer do serviço (**painel não verificável no repo**).
- Para correlacionar uma requisição ponta a ponta: capture o `X-Request-ID` da resposta e filtre o log por ele.
- Quem loga o quê: `SecurityFilter` → WARN em token inválido; `GlobalExceptionHandler` → ERROR + stack só em 5xx; services → INFO em transições de estado de negócio; controllers e `RequestIdFilter` → nada.
- **NUNCA logar:** senha, JWT, CPF. `tenantId`/`userId` já estão no MDC — não repita no texto da mensagem.

---

## 6. Hook post-merge do SonarQube (opcional)

Ativação única: `git config core.hooksPath .githooks`. O hook `.githooks/post-merge` só age em merges na `develop`:

- **Default:** apenas lembra de rodar `./scripts/sonar-scan.sh`.
- **`SONAR_AUTO_SCAN=1`** (no `.env` ou `.env.local`; o último vence): dispara o scan completo em background, log em `.sonar-scan.log` na raiz. Pesado — roda `mvn verify` com Testcontainers.

Detalhes do scan, token e leitura do quality gate: `fintech-core-validation-and-qa`.

---

## Proveniência e manutenção

Fontes: `commands.md`, `docker-compose.yml`, `scripts/{sync-db,sync-tenant,neon-develop-reset,dev-start}.sh`, `scripts/.env.template`, `.github/workflows/ci-cd.yml`, `.githooks/post-merge`, `backend/src/main/resources/application*.properties`, `frontend/netlify.toml`, `summary.md` (logging), `RequestIdFilter.java`. Verificado em 2026-07-04.

Re-verificação rápida:

```bash
grep -E 'swagger|ddl-auto|profiles' backend/src/main/resources/application.properties   # Swagger UI + validate + perfil default
grep -A2 'Deploy:' .github/workflows/ci-cd.yml                                          # portão de deploy (comentário)
grep -E 'DATABASE_URL|logging' backend/src/main/resources/application-prod.properties   # env vars e log JSON de prod
grep -n 'recurring_budget_items' scripts/sync-tenant.sh                                 # defasagem V21 já corrigida?
cat scripts/neon-develop-reset.sh                                                       # ainda é o one-liner?
```
