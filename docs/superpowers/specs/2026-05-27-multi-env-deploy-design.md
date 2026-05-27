# Design: Deploy Multi-Ambiente (CI/CD Profissional)

**Data:** 2026-05-27  
**Status:** aprovado  
**Autor:** Sergio Henrique Costa

---

## Contexto e Motivação

O projeto possui um único `application.properties` com valores hardcoded (credenciais, URLs, flags de debug), sem Dockerfiles, sem separação de ambiente no Angular e sem pipeline de deploy automático. O CI atual (GitHub Actions) só roda testes — não há CD.

O objetivo deste design é estabelecer uma infraestrutura profissional de multi-ambiente que separa claramente desenvolvimento local de produção, garante que código com testes quebrados nunca chegue a produção e automatiza o deploy via Git.

**Princípio norteador:** 12-Factor App #3 — configuração que muda entre ambientes nunca fica no código; fica em variáveis de ambiente.

---

## Decisões Arquiteturais

| Decisão | Escolha | Motivo |
|---|---|---|
| Plataforma backend | Render | Suporte nativo a Java, tier gratuito, `render.yaml` para IaC |
| Plataforma frontend | Netlify | Líder em sites estáticos, CDN global, `netlify.toml` para IaC |
| Banco remoto | Neon (já provisionado) | PostgreSQL serverless, tier gratuito, separado do provider do app |
| Orquestrador CI/CD | GitHub Actions | Testes como portão antes do deploy; deploy via hooks |
| Estratégia de deploy | Abordagem 2 (CI Actions + CD via hooks) | Fluxo profissional sem complexidade de imagens Docker |

**Transição futura para Abordagem 3 (container-first):** a fundação deste design (profiles, env vars, Actions como orquestrador) é idêntica. A migração exige apenas escrever Dockerfiles e substituir o passo "curl deploy hook" por "docker build + push para GHCR" no workflow.

---

## Seção 1 — Separação de Configuração por Ambiente

### Backend: Spring Profiles

Três arquivos de propriedades substituem o único `application.properties` atual:

```
backend/src/main/resources/
├── application.properties          ← defaults comuns + define profile padrão como dev
├── application-dev.properties      ← overrides para desenvolvimento local
└── application-prod.properties     ← overrides para produção (sem valores reais)
```

**`application.properties`** — apenas o que é comum a todos os ambientes:

```properties
spring.application.name=fintech-api
spring.profiles.active=dev
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=validate
springdoc.api-docs.enabled=true
springdoc.swagger-ui.url=/openapi.yaml
springdoc.swagger-ui.path=/swagger-ui.html
```

**`application-dev.properties`** — configuração local completa:

```properties
# Banco local (Docker Compose)
spring.datasource.url=jdbc:postgresql://localhost:5432/fintech
spring.datasource.username=admin
spring.datasource.password=secret

# Debug
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# DevTools
spring.devtools.restart.enabled=true
spring.devtools.restart.poll-interval=2s
spring.devtools.restart.quiet-period=1s

# JWT (valor fixo apenas para dev)
api.security.token.secret=dev-secret-key-not-for-production

# CORS
cors.allowed-origins=http://localhost:4200
```

**`application-prod.properties`** — apenas referências a variáveis de ambiente:

```properties
# Banco (Neon)
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}

# Produção: sem SQL no log, sem devtools
spring.jpa.show-sql=false

# JWT
api.security.token.secret=${JWT_SECRET}

# CORS
cors.allowed-origins=${CORS_ALLOWED_ORIGINS}
```

O Render injeta `SPRING_PROFILES_ACTIVE=prod` via variável de ambiente, sobrescrevendo o `dev` definido no `application.properties` base.

### Mapeamento de valores por ambiente

| Propriedade | Dev local | Produção |
|---|---|---|
| `datasource.url` | `localhost:5432/fintech` | `${DATABASE_URL}` (Neon) |
| `datasource.username` | `admin` | `${DB_USERNAME}` |
| `datasource.password` | `secret` | `${DB_PASSWORD}` |
| `jpa.show-sql` | `true` | `false` |
| `devtools.restart.enabled` | `true` | ausente |
| `jwt.secret` | valor fixo de dev | `${JWT_SECRET}` |
| `cors.allowed-origins` | `http://localhost:4200` | `${CORS_ALLOWED_ORIGINS}` |

### Frontend: Angular Environments

Criar a pasta `environments/` e dois arquivos:

```
frontend/src/environments/
├── environment.ts             ← dev (apiUrl vazio — proxy lida com o roteamento)
└── environment.production.ts  ← prod (apiUrl aponta para Render)
```

**`environment.ts`:**
```typescript
export const environment = {
  production: false,
  apiUrl: ''  // proxy.conf.json redireciona /api e /auth para localhost:8080
};
```

**`environment.production.ts`:**
```typescript
export const environment = {
  production: true,
  apiUrl: 'https://fintech-api.onrender.com'  // atualizar com URL real após criar serviço no Render
};
```

Adicionar `fileReplacements` no `angular.json` na configuração `production`:
```json
"fileReplacements": [
  {
    "replace": "src/environments/environment.ts",
    "with": "src/environments/environment.production.ts"
  }
]
```

Services que fazem chamadas HTTP devem usar `environment.apiUrl` como prefixo das URLs — em dev retorna string vazia (proxy assume), em prod retorna a URL completa do Render.

---

## Seção 2 — Pipeline CI/CD no GitHub Actions

### Consolidação dos workflows

Os três workflows atuais (`backend-ci.yml`, `frontend-ci.yml`, `maven.yml`) são substituídos por um único `ci-cd.yml`.

**Comportamento por branch:**
- Push/PR para `develop` → apenas CI (testes, sem deploy)
- Push para `main` → CI + CD (testes → deploy se passar)

### Arquivo `.github/workflows/ci-cd.yml`

```yaml
name: CI/CD

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  test-backend:
    name: Testes Backend
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ./backend
    steps:
      - uses: actions/checkout@v4
      - name: Configura JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      - name: Roda testes e empacota
        run: ./mvnw verify

  test-frontend:
    name: Testes Frontend
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ./frontend
    steps:
      - uses: actions/checkout@v4
      - name: Configura Node.js 22
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json
      - name: Instala dependências
        run: npm ci
      - name: Roda testes
        run: npm test
      - name: Build de produção
        run: npm run build -- --configuration=production

  deploy:
    name: Deploy para Produção
    runs-on: ubuntu-latest
    needs: [test-backend, test-frontend]
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    steps:
      - name: Aciona deploy do backend (Render)
        run: |
          curl -X POST "${{ secrets.RENDER_DEPLOY_HOOK_URL }}"
      - name: Aciona deploy do frontend (Netlify)
        run: |
          curl -X POST "${{ secrets.NETLIFY_DEPLOY_HOOK_URL }}"
```

### Secrets necessários no GitHub

Configurados em _Settings → Secrets and variables → Actions → Repository secrets_:

| Secret | Descrição |
|---|---|
| `RENDER_DEPLOY_HOOK_URL` | URL gerada no dashboard do Render (Deploy Hook) |
| `NETLIFY_DEPLOY_HOOK_URL` | URL gerada no dashboard do Netlify (Build Hook) |

Os demais secrets (banco, JWT, CORS) são configurados **no dashboard do Render** e nunca passam pelo GitHub.

---

## Seção 3 — Configuração das Plataformas (IaC)

### `render.yaml` (raiz do repositório)

```yaml
services:
  - type: web
    name: fintech-api
    runtime: java
    buildCommand: cd backend && ./mvnw package -DskipTests
    startCommand: java -jar backend/target/api-0.0.1-SNAPSHOT.jar
    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: prod
      - key: JWT_SECRET
        sync: false
      - key: DATABASE_URL
        sync: false
      - key: DB_USERNAME
        sync: false
      - key: DB_PASSWORD
        sync: false
      - key: CORS_ALLOWED_ORIGINS
        sync: false
```

`sync: false` significa que o valor não está no arquivo — é preenchido manualmente uma única vez no dashboard do Render e fica persistido lá.

### `netlify.toml` (raiz do repositório)

```toml
[build]
  base    = "frontend"
  command = "npm ci && npm run build -- --configuration=production"
  publish = "dist/frontend/browser"

[[redirects]]
  from   = "/*"
  to     = "/index.html"
  status = 200
```

O bloco `[[redirects]]` é obrigatório para SPAs: sem ele, um refresh direto em `/accounts/123` retorna 404 porque o Netlify procura um arquivo físico naquele caminho.

### Secrets no Render (dashboard)

Configurados uma única vez em _Environment → Environment Variables_:

| Variável | Valor |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (já no `render.yaml`) |
| `JWT_SECRET` | segredo forte gerado para produção |
| `DATABASE_URL` | connection string do Neon (`postgresql://...?sslmode=require`) |
| `DB_USERNAME` | usuário do Neon |
| `DB_PASSWORD` | senha do Neon |
| `CORS_ALLOWED_ORIGINS` | URL do site no Netlify (ex: `https://fintech-app.netlify.app`) |

---

## Seção 4 — Experiência de Desenvolvimento Local

### O que não muda

O `docker-compose.yml` atual (PostgreSQL + pgAdmin) permanece idêntico. O fluxo local continua:

```bash
docker compose up -d          # sobe o banco
./mvnw spring-boot:run        # backend na porta 8080
npm start                     # frontend na porta 4200
```

### Como o profile dev é ativado automaticamente

`application.properties` define `spring.profiles.active=dev` como padrão. Em produção, o Render injeta `SPRING_PROFILES_ACTIVE=prod` via variável de ambiente do sistema operacional, que o Spring Boot prioriza sobre o arquivo de propriedades. O desenvolvedor nunca precisa setar variáveis de ambiente localmente.

### Fluxo Git e deploy

```
feature/* → develop (PR)
  └─ Actions: CI apenas (testes backend + frontend)
  └─ sem deploy

develop → main (PR)
  └─ Actions: CI → se passou → deploy
      ├─ Render: builda JAR e sobe o serviço
      └─ Netlify: builda Angular e publica no CDN
          └─ Neon: Flyway aplica migrations no startup
```

---

## Estrutura de Arquivos: Novos e Modificados

```
fintech-core/
├── render.yaml                                        ← NOVO
├── netlify.toml                                       ← NOVO
├── .github/workflows/
│   ├── ci-cd.yml                                      ← NOVO (substitui os 3 atuais)
│   ├── backend-ci.yml                                 ← REMOVIDO
│   ├── frontend-ci.yml                                ← REMOVIDO
│   └── maven.yml                                      ← REMOVIDO
├── backend/src/main/resources/
│   ├── application.properties                         ← MODIFICADO (simplificado)
│   ├── application-dev.properties                     ← NOVO
│   └── application-prod.properties                    ← NOVO
└── frontend/
    ├── angular.json                                    ← MODIFICADO (fileReplacements)
    └── src/environments/
        ├── environment.ts                             ← NOVO
        └── environment.production.ts                 ← NOVO
```

---

## O Que Este Design Ensina

| Conceito | Onde aparece |
|---|---|
| **Spring Profiles** | Como o Spring resolve configuração por ambiente em tempo de inicialização |
| **12-Factor App #3** | Separação de config e código — o padrão que toda empresa séria segue |
| **GitHub Actions como portão** | Testes não são decoração — são a condição para o deploy existir |
| **Deploy Hooks** | Como plataformas gerenciadas expõem controle programático via HTTP |
| **Infrastructure as Code** | `render.yaml` e `netlify.toml` — infra versionada junto com o código |
| **SPA redirect** | Por que SPAs precisam de fallback para `index.html` em servidores estáticos |

---

## Notas para o Futuro

**Neon Database Branching:** o Neon suporta branches de banco (similar a Git). É possível ter um branch `main` para produção e um branch `develop` para testar migrations sem afetar prod. Vale explorar quando o projeto ganhar mais maturidade.

**Transição para Abordagem 3 (container-first):** quando o momento chegar, a mudança é cirúrgica:
1. Adicionar `Dockerfile` no backend e no frontend
2. No `ci-cd.yml`, substituir o passo de deploy por `docker build + push para GHCR`
3. Reconfigurar o Render para puxar imagem do GHCR em vez de buildar do código
4. Os profiles, env vars e secrets permanecem idênticos
