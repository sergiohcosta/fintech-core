# Tech Stack & Build

## Backend

| Component | Technology |
|-----------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 4.0.1 |
| Security | Spring Security + JWT (Auth0 java-jwt 4.4.0) |
| ORM | Spring Data JPA / Hibernate |
| Migrations | Flyway (PostgreSQL dialect) |
| Validation | Jakarta Bean Validation (spring-boot-starter-validation) |
| API Docs | springdoc-openapi 2.8.9 (Swagger UI) |
| Code Generation | openapi-generator-maven-plugin 7.4.0 (interfaceOnly=true) |
| Utilities | Lombok |
| Testing | JUnit 5, Mockito, AssertJ, Spring MockMvc, spring-security-test |
| Build Tool | Maven (wrapper: `./mvnw`) |

## Frontend

| Component | Technology |
|-----------|-----------|
| Framework | Angular 21 (Zoneless, Signals-first) |
| UI Library | Angular Material 3 |
| State | Signals (`signal()`, `computed()`, `effect()`); RxJS only for HTTP/async streams |
| API Client | Orval (generated from OpenAPI spec) |
| Testing | Vitest 4.x |
| Language | TypeScript 5.9 (strict mode, no `any`) |
| Package Manager | npm 11.x |
| Formatting | Prettier (100 col, single quotes, angular HTML parser) |

## Database

- PostgreSQL 16 (Docker: `postgres:16-alpine`)
- Local admin: pgAdmin at `localhost:5050`

## API Contract (Spec-First)

Source of truth: `api-spec/openapi.yaml`

Flow:
1. Edit `api-spec/openapi.yaml`
2. Backend: `./mvnw generate-sources` → Spring interfaces in `target/` (not committed)
3. Frontend: `npm run api:generate` → generated services in `frontend/src/app/core/api/`
4. Copy spec: `cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml`
