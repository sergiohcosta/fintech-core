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
│       ├── db/seed/             # V13__seed_dev.sql (perfil dev)
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
    ├── superpowers/specs/       # Specs de design SDD (YYYY-MM-DD-{feature}-design.md)
    ├── superpowers/plans/       # Planos de execução (multi-agency)
    ├── adr/                     # Architecture Decision Records (ADR-001…)
    └── http/                    # Coleções HTTP (.http) + seed-dataset
```
