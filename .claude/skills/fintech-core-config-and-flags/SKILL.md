---
name: fintech-core-config-and-flags
description: >
  Catálogo completo de todo eixo de configuração do fintech-core: perfis Spring
  (application.properties base, dev, local, prod), properties e defaults, variáveis de
  ambiente de produção (Railway: PORT, DATABASE_URL, JWT_SECRET, CORS_ALLOWED_ORIGINS),
  NeonFallbackEnvironmentPostProcessor (neon.enabled), test resources que SUBSTITUEM o
  classpath principal, environments Angular (apiUrl, devCredentials, proxy.conf.json,
  fileReplacements), .env dos scripts (DATABASE_URL_NEON, DATABASE_URL_RAILWAY,
  SYNC_TENANT_ID, SONAR_TOKEN, SONAR_AUTO_SCAN), docker-compose (portas e credenciais
  locais), knobs do Orval e do openapi-generator, coverage excludes do Vitest.
  Use quando a pergunta envolver: configuração, property, profile, perfil Spring,
  environment variable, env var, feature flag, CORS, JWT secret, datasource, .env,
  proxy, porta, credencial local, onde configurar X, ou como adicionar um novo eixo
  de config. Verificado contra o repo em 2026-07-04.
---

# Configuração e Flags — fintech-core

Toda configuração do projeto, eixo por eixo: **onde vive, qual o default, quem consome,
e como se comporta em dev vs. produção**. Ground truth: cada fato abaixo foi lido dos
arquivos reais em 2026-07-04.

## Quando NÃO usar esta skill

| Necessidade | Skill correta |
|---|---|
| Montar o ambiente do zero (versões, docker compose up, seeds, primeiro build) | `fintech-core-build-and-env` |
| Rodar a aplicação, deploy, sync-db/sync-tenant na prática | `fintech-core-run-and-operate` |
| Pipeline de regeneração OpenAPI/Orval (api-sync.sh, ordem dos passos) | `fintech-core-build-and-env` |
| Sintoma de config quebrada (Flyway checksum, ctx de teste não sobe) | `fintech-core-debugging-playbook` |
| Mudar comportamento (nova property de negócio passa pelo ciclo SDD) | `fintech-core-change-control` |

---

## Mapa geral dos eixos de configuração

| Eixo | Arquivo | Default | Consumidor | Prod? |
|---|---|---|---|---|
| Perfil ativo | `backend/src/main/resources/application.properties` | `spring.profiles.active=dev` | Spring Boot | Railway ativa `prod` via env var (fora do repo) |
| ddl-auto | `application.properties` | `validate` (nunca `update`) | Hibernate | igual (herda da base) |
| Datasource dev | `application-dev.properties` | `jdbc:postgresql://localhost:5432/fintech`, `admin`/`secret` | HikariCP | não — prod usa `${DATABASE_URL}` |
| Fallback Neon | `application-dev.properties` → `neon.enabled=false` | `false` no arquivo (`true` no código se ausente) | `NeonFallbackEnvironmentPostProcessor` | não roda (depende de `application-local.properties`) |
| Credenciais Neon develop | `application-local.properties` (**NÃO commitado**, secreto) | `neon.datasource.{url,username,password}` | idem acima | não |
| JWT secret | `application-dev.properties` | `dev-secret-key-not-for-production` | `TokenService` (`@Value("${api.security.token.secret}")`) | `${JWT_SECRET}` |
| CORS | `application-dev.properties` | `http://localhost:4200` | `SecurityConfigurations` (`@Value("${cors.allowed-origins}")`) | `${CORS_ALLOWED_ORIGINS}` |
| Flyway locations | `application-dev.properties` | `classpath:db/migration,classpath:db/seed` | Flyway | prod herda default (só `db/migration`) |
| Logging | dev: pattern MDC legível · prod: `logging.structured.format.console=logstash` | ver seções abaixo | Logback/Spring | sim |
| Porta backend | `application-prod.properties` | `${PORT:8080}` | servidor embutido | Railway injeta `PORT` |
| Hikari (Neon serverless) | `application-prod.properties` e post-processor | `max-lifetime=600000`, `keepalive-time=300000` | HikariCP | sim |
| Swagger/OpenAPI UI | `application.properties` | `springdoc.swagger-ui.url=/openapi.yaml`, path `/swagger-ui.html` | springdoc | sim (herda) |
| API URL frontend | `frontend/src/environments/environment.ts` | `apiUrl: ''` (usa proxy) | `core/interceptors/api-url.interceptor.ts` | `environment.production.ts` → `https://fintech-core-production.up.railway.app` |
| Credenciais dev no login | `environment.ts` | `carlos@costa.com` / `costa123` | `features/auth/login/login.ts` | `null` em produção |
| Proxy dev Angular | `frontend/proxy.conf.json` | `/auth`, `/api`, `/invites` → `http://localhost:8080` | dev-server (`angular.json:74` `proxyConfig`) | não existe em prod |
| Scripts .env | `.env` / `.env.local` na raiz (template: `scripts/.env.template`) | ver tabela dedicada | `sync-db.sh`, `sync-tenant.sh`, `sonar-scan.sh`, `.githooks/post-merge` | não |
| Banco local | `docker-compose.yml` | postgres:16-alpine, db `fintech`, `admin`/`secret`, porta 5432 | backend dev + scripts | não |
| pgAdmin | `docker-compose.yml` | porta 5050, `admin@fintech.com`/`admin` | dev humano | não |
| Codegen frontend | `frontend/orval.config.ts` | `tags-split`, client `angular`, target `src/app/core/api` | Orval | n/a (build) |
| Codegen backend | `backend/pom.xml` (openapi-generator 7.4.0) | `interfaceOnly=true`, `generateModels=false` | Maven generate-sources | n/a (build) |
| Coverage frontend | `frontend/package.json` script `test:cov` | exclui `src/app/core/api/**` e `**/*.spec.ts` | Vitest/`@angular/build:unit-test` | n/a |

---

## 1. Perfis Spring (backend)

Quatro arquivos em `backend/src/main/resources/` + um em `backend/src/test/resources/`.
Resolução: a base sempre carrega; o perfil ativo (default `dev`) faz overlay; o
post-processor da Neon pode sobrescrever o datasource por cima de tudo.

### 1.1 `application.properties` (base — vale para todos os perfis)

```properties
spring.application.name=fintech-api
spring.profiles.active=dev
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=validate
springdoc.api-docs.enabled=true
springdoc.swagger-ui.url=/openapi.yaml
springdoc.swagger-ui.path=/swagger-ui.html
```

`ddl-auto=validate` é deliberado: schema só muda via migration Flyway (regra inviolável
do CLAUDE.md). Nunca trocar para `update`.

### 1.2 `application-dev.properties` (perfil `dev` — dia a dia)

| Grupo | Property | Valor |
|---|---|---|
| Banco local (Docker) | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/fintech` |
| | `spring.datasource.username` / `password` | `admin` / `secret` |
| Flag Neon | `neon.enabled` | `false` (banco local sempre) |
| Debug SQL | `spring.jpa.show-sql` + `hibernate.format_sql` | `true` / `true` |
| Logging | `logging.pattern.console` | pattern legível com MDC: `%X{requestId:--}` e `%X{userId:--}` |
| | `logging.level.com.fintech` | `INFO` (`org.springframework.web` e `.security` em `WARN`) |
| DevTools | `spring.devtools.restart.{enabled,poll-interval,quiet-period}` | `true` / `2s` / `1s` |
| JWT | `api.security.token.secret` | `dev-secret-key-not-for-production` |
| CORS | `cors.allowed-origins` | `http://localhost:4200` |
| Flyway | `spring.flyway.locations` | `classpath:db/migration,classpath:db/seed` — **é aqui que os seeds V13/V16/V18/V20 entram, só em dev** |

### 1.3 `application-local.properties` (SECRETO — não commitado)

Confirmado em 2026-07-04: `git ls-files` não lista o arquivo; ele está ignorado por
`backend/.gitignore:40` (`src/main/resources/application-local.properties`). Contém as
credenciais reais da branch develop da Neon — **nunca copiar valores reais para docs,
skills ou commits**. Formato (valores mascarados):

```properties
neon.datasource.url=jdbc:postgresql://<host-neon>.aws.neon.tech/neondb?sslmode=require&channel_binding=require
neon.datasource.username=<user>
neon.datasource.password=<senha>
```

Só é lido pelo post-processor abaixo — o Spring não o carrega como perfil (`local`
nunca está em `spring.profiles.active`).

### 1.4 `NeonFallbackEnvironmentPostProcessor` — como `neon.enabled` funciona

Classe: `backend/src/main/java/com/fintech/api/config/NeonFallbackEnvironmentPostProcessor.java`.
Registrada em `backend/src/main/resources/META-INF/spring.factories` como
`org.springframework.boot.EnvironmentPostProcessor`. Ordem: `HIGHEST_PRECEDENCE + 15`
(roda antes de qualquer bean).

Fluxo, na ordem:

1. Lê `neon.enabled` do Environment. **Default no código é `true`** quando a property
   está ausente — mas `application-dev.properties` fixa `false`, então em dev padrão o
   post-processor sai imediatamente e usa o banco local.
2. Se habilitado: carrega `application-local.properties` direto do classpath (não via
   Spring). Arquivo ausente ou sem `neon.datasource.url` → sai silenciosamente (fallback local).
3. Teste TCP no host da Neon, porta 5432, timeout 2000 ms.
4. Teste JDBC completo (TCP + SSL + auth), `loginTimeout`/`socketTimeout`/`connectTimeout` = 3 s
   — cobre redes que abrem TCP mas bloqueiam o handshake SSL.
5. Ambos ok → `environment.getPropertySources().addFirst(...)` com um `MapPropertySource`
   chamado `neon-datasource` contendo `spring.datasource.{url,username,password}` +
   `hikari.max-lifetime=600000` e `hikari.keepalive-time=300000` (Neon é serverless,
   conexões idle caem). `addFirst` = vence qualquer properties file.
6. Cada saída imprime uma linha `>>> [NeonFallback] ...` no stdout dizendo qual banco venceu.

Para forçar um lado: `neon.enabled=false` → sempre local; `neon.enabled=true` **e**
`application-local.properties` presente → tenta Neon com fallback automático para local.

### 1.5 `application-prod.properties` (perfil `prod` — Railway)

```properties
server.port=${PORT:8080}
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.hikari.max-lifetime=600000
spring.datasource.hikari.keepalive-time=300000
spring.jpa.show-sql=false
logging.structured.format.console=logstash
logging.level.com.fintech=INFO
logging.level.org.springframework.web=WARN
logging.level.org.springframework.security=WARN
api.security.token.secret=${JWT_SECRET}
cors.allowed-origins=${CORS_ALLOWED_ORIGINS}
```

**Variáveis de ambiente exigidas em produção** (injetadas no painel do Railway;
os comentários dentro do arquivo ainda dizem "Render" — comentário desatualizado,
o deploy real é Railway, cf. `environment.production.ts` e ADRs):

| Env var | Papel | Obrigatória? |
|---|---|---|
| `PORT` | porta HTTP | não (default 8080) |
| `DATABASE_URL` | JDBC URL da Neon (produção) | sim — startup falha sem ela |
| `DB_USERNAME` / `DB_PASSWORD` | credenciais do banco | sim |
| `JWT_SECRET` | assinatura dos tokens | sim |
| `CORS_ALLOWED_ORIGINS` | URL do site no Netlify | sim |
| `SPRING_PROFILES_ACTIVE=prod` | ativa o perfil (a base hardcoda `dev`) | sim — configurada no Railway, não visível no repo (**não verificável aqui**) |

Não há nenhum arquivo de manifesto Railway/Netlify commitado no repo — a configuração
de deploy vive nos painéis das plataformas.

### 1.6 `backend/src/test/resources/application-dev.properties` — SUBSTITUI, não faz merge

Gotcha documentado na memória do projeto: quando existe um `application-dev.properties`
no test-classpath, ele **substitui por completo** o do main-classpath (Flyway/Spring não
fazem merge). Consequência: **toda property necessária tem de ser replicada** ali. O
arquivo atual replica: datasource local, `show-sql`/`format_sql`, pattern de logging MDC,
níveis de log, `api.security.token.secret`, `cors.allowed-origins`. E diverge de propósito
em dois pontos:

- `spring.flyway.locations=classpath:db/migration` (sem `db/seed`) +
  `spring.flyway.ignore-migration-patterns=*:missing,*:future` — os seeds estão aplicados
  no banco mas ausentes do classpath de teste; `*:missing` cobre seeds abaixo do máximo
  de `db/migration`, `*:future` cobre seeds acima (ex.: V18 > V17).
- `spring.datasource.hikari.maximum-pool-size=3` e `minimum-idle=1` — vários
  `@SpringBootTest` simultâneos esgotariam o `max_connections=100` do Postgres.

Ao adicionar property nova em `application-dev.properties` (main), pergunte-se sempre:
os testes de integração precisam dela? Se sim, replicar no arquivo de test resources
**na mesma entrega** (o cabeçalho do próprio arquivo manda manter sincronizado).

---

## 2. Frontend Angular

### 2.1 Environments

| Arquivo | `production` | `apiUrl` | `devCredentials` |
|---|---|---|---|
| `frontend/src/environments/environment.ts` | `false` | `''` (vazio → requisições relativas, atendidas pelo proxy do dev-server) | `carlos@costa.com` / `costa123` (pré-preenche o form em `features/auth/login/login.ts`) |
| `frontend/src/environments/environment.production.ts` | `true` | `https://fintech-core-production.up.railway.app` | `null` |

- `apiUrl` é consumido por `src/app/core/interceptors/api-url.interceptor.ts` (prefixa as
  chamadas HTTP) — registrado em `app.config.ts`.
- A troca dev→prod é por `fileReplacements` na configuração `production` de
  `frontend/angular.json` (linhas 37–41); `defaultConfiguration` do build é `production`.

### 2.2 Proxy de desenvolvimento — `frontend/proxy.conf.json`

Existe e está ligado ao dev-server em `angular.json:74` (`"proxyConfig": "proxy.conf.json"`).
Encaminha `/auth`, `/api` e `/invites` para `http://localhost:8080` (`secure: false`,
`changeOrigin: true`). É por isso que `apiUrl` pode ser vazio em dev. **Novo prefixo de
rota no backend fora de `/api` exige nova entrada aqui** (foi o caso de `/invites`).

### 2.3 Knobs de teste

- `frontend/vitest.config.ts`: plugin `@analogjs/vite-plugin-angular`, `environment: 'jsdom'`,
  `setupFiles: ['./src/test-setup.ts']`.
- `angular.json`: target de teste usa builder `@angular/build:unit-test` — daí a regra de
  rodar specs de componente via `ng test`/`npm test`, nunca `npx vitest` cru.
- `package.json` → `test:cov`: `ng test --watch=false --coverage` com
  `--coverage-exclude "src/app/core/api/**"` (código gerado pelo Orval não conta em
  cobertura) e `--coverage-exclude "**/*.spec.ts"`; reporters `lcovonly` + `text`.

---

## 3. `.env` dos scripts (raiz do repo)

Template: `scripts/.env.template`. Os scripts carregam `.env` e depois `.env.local`
(o último vence). Ambos ignorados pelo git (`.gitignore` linhas 7–8). **Contêm segredos —
nunca commitá-los nem colar valores em docs.**

| Variável | Default | Consumidores (verificado por grep em 2026-07-04) |
|---|---|---|
| `DATABASE_URL_NEON` | — (obrigatória) | `scripts/sync-db.sh` (dump Neon → restore local) |
| `DATABASE_URL_RAILWAY` | — (obrigatória para sync-tenant) | `scripts/sync-tenant.sh` (URL PÚBLICA do Postgres Railway, formato libpq, não jdbc) |
| `DATABASE_URL_LOCAL` | `postgresql://admin:secret@localhost:5432/fintech` | `scripts/sync-tenant.sh` (override opcional do banco local; não consta no `.env.template` — lida só pelo sync-tenant.sh, com este default) |
| `SYNC_TENANT_ID` | — (obrigatória para sync-tenant) | `scripts/sync-tenant.sh` (UUID do tenant-alvo) |
| `SONAR_HOST_URL` | `http://localhost:9000` | `scripts/sonar-scan.sh` |
| `SONAR_TOKEN` | — (obrigatória para scan; token `sqa_`) | `scripts/sonar-scan.sh` (backend via `-Dsonar.token`, frontend via env) e chamadas `curl` do skill sonar-status |
| `SONAR_AUTO_SCAN` | `0` (só lembra) | `.githooks/post-merge` — `1` roda `sonar-scan.sh` em background a cada merge na develop |

---

## 4. `docker-compose.yml` (infra local)

| Serviço | Imagem | Porta | Credenciais | Extras |
|---|---|---|---|---|
| `postgres` (container `fintech-postgres`) | `postgres:16-alpine` | `5432:5432` | db `fintech`, user `admin`, senha `secret` | volume `./.docker/postgres-data`, rede `fintech-net`, `restart: unless-stopped` |
| `pgadmin` (container `fintech-pgadmin`) | `dpage/pgadmin4` | `5050:80` | `admin@fintech.com` / `admin` | opcional, `depends_on: postgres` |

Essas credenciais são a fonte dos defaults espelhados em `application-dev.properties`,
no test resources e no `DATABASE_URL_LOCAL` — mudar uma exige mudar as quatro.

---

## 5. Knobs de codegen (só configuração — pipeline em build-and-env)

- **Orval** (`frontend/orval.config.ts`): input `../api-spec/openapi.yaml`; output
  `mode: 'tags-split'`, `target: 'src/app/core/api'`, `client: 'angular'`,
  `override.angular.provideIn: 'root'`. Efeito colateral conhecido: regenera
  `auth/auth.service.ts`, que precisa ser removido (o `api-sync.sh` faz isso).
- **openapi-generator** (`backend/pom.xml`, plugin 7.4.0): `generatorName=spring`,
  `inputSpec` aponta para o mesmo `api-spec/openapi.yaml`, output em
  `target/generated-sources/openapi` (não commitado), `generateModels=false`,
  `apiPackage=com.fintech.api.openapi`, e `configOptions`: `interfaceOnly=true`,
  `useSpringBoot3=true`, `useTags=true`, `useJakartaEe=true`, `skipDefaultInterface=true`.
  Os DTOs próprios entram via `schemaMappings`/`importMappings` — **todo schema novo na
  spec com DTO manual exige uma entrada nova nesses mappings**.
- Como regenerar (ordem, script `api-sync.sh`): ver `fintech-core-build-and-env`.

---

## 6. Checklist — adicionar um novo eixo de configuração

1. **Base ou por perfil?** Igual em todo lugar → `application.properties`. Difere entre
   dev e prod → uma linha em `application-dev.properties` (valor fixo de dev) e outra em
   `application-prod.properties` (placeholder `${MINHA_ENV_VAR}`).
2. **Prod:** criar a variável no painel do Railway (nada disso é versionado). Sem default
   embutido → startup falha rápido, o que é desejável para segredos.
3. **Test resources:** se testes de integração tocam o eixo, replicar em
   `backend/src/test/resources/application-dev.properties` — ele substitui o main, não
   faz merge.
4. **Segredo?** Nunca em arquivo commitado. Opções existentes: env var (prod),
   `application-local.properties` (padrão Neon, com entrada no `backend/.gitignore`),
   ou `.env`/`.env.local` (scripts). Atualizar `scripts/.env.template` com placeholder
   e comentário de onde obter o valor.
5. **Frontend?** Campo novo nos DOIS environments (`environment.ts` e
   `environment.production.ts` — TypeScript quebra se os shapes divergirem, ver o
   `devCredentials: null as {...} | null`). Rota backend nova fora de `/api|/auth|/invites`
   → entrada no `proxy.conf.json`.
6. **Consumo:** backend via `@Value("${...}")` (padrão atual em `TokenService` e
   `SecurityConfigurations`); frontend via import de `environment`.
7. **Documentar:** default e propósito em comentário no próprio properties (estilo da
   casa) e, se for regra de operação, rotear via `fintech-core-change-control` para
   atualizar `summary.md`/spec. Atualizar ESTA skill (tabela do mapa geral).
8. **Dataset/seed:** eixo que muda schema ou dados → regra do dataset se aplica
   (ver `fintech-core-change-control`).

---

## 7. Runbook de re-verificação (config drifta)

```bash
# Diff main vs. test resources (devem estar em sincronia, exceto flyway/hikari):
diff backend/src/main/resources/application-dev.properties backend/src/test/resources/application-dev.properties

# O que mudou nos properties recentemente:
git log --oneline -10 -- backend/src/main/resources/*.properties backend/src/test/resources/

# Confirmar que nenhum segredo entrou no git:
git ls-files | grep -E 'application-local|\.env'          # deve retornar vazio

# Conferir env vars placeholders exigidas em prod:
grep -o '\${[A-Z_]*}' backend/src/main/resources/application-prod.properties

# Qual banco o backend escolheu no último start (dev):
grep 'NeonFallback' <log do backend>                       # linha ">>> [NeonFallback] ..."

# Portas/credenciais locais:
grep -E 'POSTGRES_|ports' docker-compose.yml
```

---

## Proveniência e manutenção

Todos os fatos lidos diretamente do repo em **2026-07-04** (branch `develop`,
HEAD `c18941e`): `backend/src/main/resources/{application,application-dev,application-local,application-prod}.properties`,
`backend/src/test/resources/application-dev.properties`, `META-INF/spring.factories`,
`NeonFallbackEnvironmentPostProcessor.java`, `frontend/src/environments/*.ts`,
`frontend/proxy.conf.json`, `frontend/angular.json`, `frontend/vitest.config.ts`,
`frontend/orval.config.ts`, `frontend/package.json`, `backend/pom.xml`,
`docker-compose.yml`, `scripts/.env.template`, `scripts/{sync-db,sync-tenant,sonar-scan}.sh`,
`.githooks/post-merge`, `.gitignore`, `backend/.gitignore`. Único fato não verificável
no repo: as env vars efetivamente setadas no painel do Railway (marcado no texto).

Re-verificação de uma linha por seção:
- Perfis: `ls backend/src/main/resources/*.properties && git log -3 --oneline -- backend/src/main/resources/`
- Neon fallback: `grep -n 'neon.enabled\|addFirst' backend/src/main/java/com/fintech/api/config/NeonFallbackEnvironmentPostProcessor.java`
- Environments Angular: `cat frontend/src/environments/*.ts frontend/proxy.conf.json`
- .env: `diff <(grep -oE '^[A-Z_]+=' scripts/.env.template) <(grep -oE '^[A-Z_]+=' .env)` (rodar localmente; não expor valores)
- Codegen: `grep -n interfaceOnly backend/pom.xml && cat frontend/orval.config.ts`
