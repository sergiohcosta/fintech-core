# Project Structure

```
fintech-core/
├── api-spec/                    # OpenAPI 3 spec (source of truth for contracts)
│   └── openapi.yaml
├── backend/                     # Java 21 + Spring Boot 4
│   ├── pom.xml                  # Maven build with openapi-generator plugin
│   ├── src/main/java/com/fintech/api/
│   │   ├── config/              # Security, JWT filter, CORS, request ID
│   │   ├── controller/          # REST endpoints (one per domain)
│   │   ├── domain/              # JPA entities organized by subdomain
│   │   │   ├── account/
│   │   │   ├── budget/
│   │   │   ├── category/
│   │   │   ├── enums/
│   │   │   ├── installment/
│   │   │   ├── invitation/
│   │   │   ├── invoice/
│   │   │   ├── tenant/
│   │   │   ├── transaction/
│   │   │   └── user/
│   │   ├── dto/                 # Request/response DTOs (mirrors domain packages)
│   │   ├── exception/           # GlobalExceptionHandler, custom exceptions
│   │   ├── repository/          # Spring Data JPA repositories
│   │   └── service/             # Business logic (one per domain)
│   └── src/main/resources/
│       ├── db/migration/        # Flyway schema (V1–V12)
│       ├── db/seed/             # V13__seed_dev.sql (dev profile)
│       └── application.properties
├── frontend/                    # Angular 21 Zoneless
│   ├── package.json
│   ├── orval.config.ts          # API client generation config
│   ├── vitest.config.ts
│   └── src/app/
│       ├── core/                # Services, interceptors, guards, models, API client
│       ├── components/          # Shared/reusable UI components
│       └── features/            # Lazy-loaded feature modules
│           ├── account/
│           ├── auth/
│           ├── category/
│           ├── dashboard/
│           ├── invoice/
│           ├── planning/
│           ├── team/
│           └── transaction/
├── scripts/                     # Dev utility scripts (DB sync, reset, env template)
├── docker-compose.yml           # PostgreSQL 16 + pgAdmin
└── docs/
    ├── superpowers/specs/       # SDD design specs (YYYY-MM-DD-{feature}-design.md)
    ├── superpowers/plans/       # Execution plans (multi-agency)
    ├── adr/                     # Architecture Decision Records (ADR-001…)
    └── http/                    # HTTP collections (.http) + seed-dataset
```

## Architecture Pattern

**Backend:** Controller → Service → Repository (layered). Hybrid package structure — top-level by layer, domain entities in subpackages.

**Frontend:** Feature-based modules with lazy loading. Each feature folder contains its own components, services, and routes. Shared code lives in `core/` and `components/`.

## Key Conventions

- **Entities** live in `domain/{subdomain}/` — never exposed outside the service layer
- **DTOs** mirror domain structure in `dto/{subdomain}/` — all API boundaries use DTOs
- **Controllers** are thin — delegate immediately to services
- **Services** own business logic and transaction boundaries
- **Repositories** contain custom JPQL queries; complex logic stays in services
- **Migrations** are immutable once applied; fixes always create a new version
- **Frontend features** are self-contained; cross-feature communication goes through `core/` services
- **Pure utility functions** go in separate files (no Angular imports) for easy unit testing with Vitest
