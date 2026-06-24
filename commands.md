## Common Commands

### Infrastructure
```bash
docker compose up -d          # Start PostgreSQL + pgAdmin
```

### Backend
```bash
cd backend
./mvnw spring-boot:run        # Run API (localhost:8080)
./mvnw test                   # Run tests
./mvnw generate-sources       # Regenerate OpenAPI interfaces
./mvnw clean install          # Full build
```

### Frontend
```bash
cd frontend
npm install                   # Install dependencies
npm start                     # Dev server (localhost:4200)
npm test                      # Run Vitest
npm run api:generate          # Regenerate API client from OpenAPI spec
```

### Análise de Código (SonarQube)

Instância local do SonarQube Community. Pré-requisito: `SONAR_TOKEN` no `.env`
(gerar na UI: My Account → Security). Backend exige Docker de pé (a análise roda
`mvn verify` com Testcontainers).

```bash
./scripts/sonar-scan.sh            # back + front
./scripts/sonar-scan.sh backend    # só Java   (projeto fintech-core-backend)
./scripts/sonar-scan.sh frontend   # só TS     (projeto fintech-core-frontend)
```

Projetos são auto-provisionados no 1º scan. Resultados em `http://localhost:9000`.

Opcional: o hook `.githooks/post-merge` lembra (ou roda) a análise a cada merge na
`develop`. Ativar uma vez: `git config core.hooksPath .githooks`. Por padrão só lembra;
para auto-scan em background, defina `SONAR_AUTO_SCAN=1` no `.env` (pesa — `mvn verify`).

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

### Sincronização do Banco (Neon → Local)

Clona o banco da Neon.tech (fonte da verdade) para o Docker local via `scripts/sync-db.sh`.

**Pré-requisitos:** Docker rodando, `pg_dump` instalado localmente, e um `.env` (ou `.env.local`) na raiz baseado em `scripts/.env.template` com `DATABASE_URL_NEON` preenchida:
```
DATABASE_URL_NEON=postgresql://[user]:[password]@[host]/[dbname]?sslmode=require
```

```bash
docker compose up -d          # Container fintech-postgres precisa estar de pé
./scripts/sync-db.sh          # Dump da Neon → reset do schema public local → restore
```

O script valida a conexão e baixa o dump antes de tocar no banco local (falha de conexão não destrói os dados locais). Reinicie o backend após a sync para refletir as mudanças.

### Sincronização de um tenant (Local ↔ Railway)

Sincroniza **apenas o tenant-alvo** entre local e Railway, num sentido por execução. Reaproveita o `.env` (precisa de `DATABASE_URL_RAILWAY` = URL pública do Railway).

```bash
./scripts/sync-tenant.sh pull   # Railway → local  (Railway é a origem)
./scripts/sync-tenant.sh push   # local   → Railway (local é a origem)
```

Cada run **substitui** os dados do tenant no destino pelos da origem (apaga + recarrega numa transação, sem merge); os demais tenants do destino não são tocados. Pede confirmação mostrando o sentido. Premissa: schema idêntico (mesmas migrations) nos dois lados.
