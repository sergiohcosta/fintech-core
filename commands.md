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
