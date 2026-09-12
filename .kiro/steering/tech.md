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
| AI / Vision | Spring AI 2.0.0-M2 (Gemini primary, Ollama fallback) |
| Recurrence | lib-recur 0.16.0 (RFC 5545 RRULE expansion) |
| CSV Parsing | Apache Commons CSV 1.14.0 |
| PDF Extraction | Apache PDFBox 3.0.7 |
| Utilities | Lombok |
| Testing | JUnit 5, Mockito, AssertJ, Spring MockMvc, spring-security-test |
| Coverage | JaCoCo + SonarQube |
| Build Tool | Maven (wrapper: `./mvnw`) |

## Frontend

| Component | Technology |
|-----------|-----------|
| Framework | Angular 21 (Zoneless, Signals-first) |
| UI Library | Angular Material 3 (^21.x, devDependency) |
| State | Signals (`signal()`, `computed()`, `effect()`); RxJS only for HTTP/async streams |
| API Client | Orval 8.x (generated from OpenAPI spec) |
| Recurrence | rrule 2.8.x (client-side RRULE expansion) |
| Testing | Vitest 4.x + fast-check (property-based) |
| Coverage | @vitest/coverage-v8 + SonarQube |
| Language | TypeScript 5.9 (strict mode, no `any`) |
| Package Manager | npm 11.x |
| Formatting | Prettier (100 col, single quotes, angular HTML parser) |

## Android

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| Build | Gradle (Kotlin DSL) |
| Min SDK | TBD (companion mobile client) |

## Testing Strategy

Mock-based — no live database in tests.
- **Service:** pure unit — `@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`, AssertJ (`assertThat`).
- **Controller:** `@SpringBootTest` + `MockMvc` (`MockMvcBuilders` + `springSecurity()`) + `@MockitoBean` (not the deprecated `@MockBean`). Cover 403 for unauthorized roles.
- **Frontend:** Vitest. Pure-logic functions in files without Angular imports (testable without `TestBed`). Property-based tests with fast-check where applicable.

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

## CI/CD & Quality

- GitHub Actions: `ci-cd.yml`, `deploy-env.yml`, `release.yml`
- SonarQube analysis (backend + frontend)
- JaCoCo coverage reports
- Deployment target: Render.com (`render.yaml`)

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
