# Project Structure

```
fintech-core/
├── api-spec/                    # OpenAPI 3 spec (source of truth for contracts)
│   └── openapi.yaml
├── backend/                     # Java 21 + Spring Boot 4
│   ├── pom.xml                  # Maven build with openapi-generator + Spring AI
│   ├── src/main/java/com/fintech/api/
│   │   ├── config/              # Security, JWT filter, CORS, request ID, VisionAiConfig
│   │   ├── controller/          # REST endpoints (15 controllers)
│   │   ├── domain/              # JPA entities organized by subdomain
│   │   │   ├── account/
│   │   │   ├── budget/
│   │   │   ├── category/
│   │   │   ├── enums/
│   │   │   ├── imports/         # ImportBatch, StagedTransaction, StagedFieldValue
│   │   │   ├── installment/
│   │   │   ├── invitation/
│   │   │   ├── invoice/
│   │   │   ├── recurrence/      # RecurrenceRule, RecurrenceException
│   │   │   ├── tenant/
│   │   │   ├── transaction/
│   │   │   └── user/
│   │   ├── dto/                 # Request/response DTOs (mirrors domain + transfer, dashboard)
│   │   ├── exception/           # GlobalExceptionHandler, custom exceptions
│   │   ├── repository/          # Spring Data JPA repositories
│   │   └── service/             # Business logic (one per domain)
│   │       ├── imports/         # ImportService, ExtractionRouter, extractors (CSV/OFX/PDF/Vision)
│   │       │   ├── vision/      # GeminiVisionClient, OllamaVisionClient, failover
│   │       │   └── templates/   # Bank-specific parsers (Itaú, Nubank)
│   │       └── recurrence/      # RecurrenceExpander, ProjectionService
│   └── src/main/resources/
│       ├── db/migration/        # Flyway schema (V1–V31, some versions skipped)
│       └── application.properties
├── frontend/                    # Angular 21 Zoneless
│   ├── package.json
│   ├── orval.config.ts          # API client generation config
│   ├── vitest.config.ts
│   └── src/app/
│       ├── core/                # Services, interceptors, guards, models, generated API client
│       │   ├── api/             # Generated Orval services (per domain)
│       │   ├── guards/
│       │   ├── interceptors/
│       │   └── services/
│       ├── components/          # Shared UI (confirmation-dialog, icon-picker, shell)
│       └── features/            # Lazy-loaded feature modules
│           ├── account/
│           ├── auth/
│           ├── category/
│           ├── dashboard/
│           ├── import/          # Import/extraction workflow UI
│           ├── invoice/
│           ├── planning/
│           ├── recurrence/      # Recurrence rule management
│           ├── team/
│           └── transaction/
├── android/                     # Kotlin/Gradle companion mobile app
├── scripts/                     # Dev utility scripts (DB sync, reset, env template, sonar)
├── docker-compose.yml           # PostgreSQL 16 + pgAdmin (dev)
├── docker-compose.prod.yml      # Production compose
├── render.yaml                  # Render.com deployment config
├── .github/                     # CI/CD workflows, issue/PR templates
└── docs/
    ├── superpowers/specs/       # SDD design specs (YYYY-MM-DD-{feature}-design.md)
    ├── superpowers/plans/       # Execution plans
    ├── adr/                     # Architecture Decision Records (ADR-001–005)
    └── http/                    # HTTP collections (.http) + seed-dataset
```

## Architecture Pattern

**Backend:** Controller → Service → Repository (layered). Hybrid package structure — top-level by layer, domain entities in subpackages. Service layer may have subpackages for complex domains (imports/vision, recurrence).

**Frontend:** Feature-based modules with lazy loading. Each feature folder contains its own components, services, and routes. Shared code lives in `core/` and `components/`.

**Import pipeline:** ExtractionRouter dispatches to CsvExtractor, OfxExtractor, PdfTextExtractor, or VisionExtractor based on file type. Vision uses Spring AI ChatClient (Gemini primary, Ollama fallback). Bank-specific templates handle known statement formats.

## Key Conventions

- **Entities** live in `domain/{subdomain}/` — never exposed outside the service layer
- **DTOs** mirror domain structure in `dto/{subdomain}/` — all API boundaries use DTOs
- **Controllers** are thin — delegate immediately to services
- **Services** own business logic and transaction boundaries
- **Repositories** contain custom JPQL queries; complex logic stays in services
- **Migrations** are immutable once applied; fixes always create a new version (current: V31)
- **Frontend features** are self-contained; cross-feature communication goes through `core/` services
- **Pure utility functions** go in separate files (no Angular imports) for easy unit testing with Vitest
- **Generated code** (Orval API client, OpenAPI Spring interfaces) is never manually edited
