# Tech Stack & Build

Versões e dependências: ver `backend/pom.xml` e `frontend/package.json` (fonte de verdade, não replicado aqui). Stack: Java 21 + Spring Boot, Angular 21 Zoneless + Signals, PostgreSQL 16, Flyway, Orval/OpenAPI Generator.

## API Contract (Spec-First)

Source of truth: `api-spec/openapi.yaml`

Flow:
1. Edit `api-spec/openapi.yaml`
2. Backend: `./mvnw generate-sources` → Spring interfaces in `target/` (not committed)
3. Frontend: `npm run api:generate` → generated services in `frontend/src/app/core/api/`
4. Copy spec: `cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml`
