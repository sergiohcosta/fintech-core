# Product Overview

Fintech Core is a multi-tenant SaaS personal/family finance management platform. A single deployment serves multiple isolated clients (families or businesses) with complete data separation enforced by tenant UUID scoping on every business query.

## Core Capabilities

- **Multi-tenancy** — full data isolation per tenant (UUID); tenant leakage is the most critical bug class
- **JWT stateless auth** — register, login, invite-based onboarding (accept-invite) for new members
- **Hierarchical categories** — multi-level tree with inheritance, soft-delete cascade, and anti-circular validation
- **Financial accounts** — 4 types: CHECKING, CASH, INVESTMENT, CREDIT_CARD (credit card with brand, limit, closing/due day)
- **Transactions** — income/expense, installment plans, credit card invoice lifecycle, transfers (double-entry)
- **Invoices** — lazy-created per credit card billing cycle; close → pay lifecycle
- **Budget planning** — recurring items, cycles, tenant settings
- **Dashboard** — period-based financial summary with liquid balance tracking

## Key Business Rules

- Passwords stored via BCrypt, never returned or logged
- Schema changes only via Flyway migrations (never `ddl-auto=update`)
- JPA entities never exposed in API responses — always DTOs
- All external IDs are UUIDs (anti-enumeration)
- Credit card transactions route through invoice lifecycle; payment creates real cash outflow
- `countInLiquidBalance` / `countInNetWorth` flags distinguish available cash from total wealth

## Language

- Codebase documentation, commits, and domain naming are in **Portuguese (PT-BR)**
- Commit messages use imperative form: `adiciona`, `corrige`, `implementa`
- Code identifiers (classes, methods, variables) are in **English**
