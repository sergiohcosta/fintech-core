# Project: Fintech SaaS Multi-Tenant

Comprehensive documentation for the Fintech SaaS platform, a multi-tenant system designed for financial management with isolation and security at its core.

## 🏛️ Project Overview

This is a Fullstack SaaS (Software as a Service) application built with a modern architecture to support multiple tenants (e.g., families, companies) within a single system instance.

### Technology Stack
- **Backend:** Java 21, Spring Boot 4.0.1, Spring Security, JPA/Hibernate.
- **Frontend:** Angular 21 (Zoneless Change Detection), Angular Material 3, Vitest.
- **Database:** PostgreSQL 16, Flyway (Migration management).
- **Infrastructure:** Docker Compose for local development (PostgreSQL, pgAdmin).
- **Authentication:** Stateless JWT (JSON Web Token).

### Architecture
- **Multi-Tenancy:** Isolation is achieved through a \`Tenant\` entity. Most entities (User, Transaction, etc.) are linked to a specific \`Tenant\` via UUIDs.
- **Security:** Every request is authenticated via a JWT token. The backend uses a \`SecurityFilter\` to validate tokens and set the security context. The frontend uses an \`authInterceptor\` to attach the token to outgoing requests.
- **Data Flow:** Uses DTOs (Data Transfer Objects) for all API communication to decouple domain models from external contracts.

---

## 🛠️ Building and Running

### 1. Infrastructure (Database)
Ensure Docker is installed and running.
\`\`\`bash
docker-compose up -d
\`\`\`
- **PostgreSQL:** \`localhost:5432\` (User: \`admin\`, Password: \`secret\`, DB: \`fintech\`)
- **pgAdmin:** \`http://localhost:5050\` (User: \`admin@fintech.com\`, Password: \`admin\`)

### 2. Backend
Navigate to the \`backend/\` directory:
\`\`\`bash
./mvnw spring-boot:run
\`\`\`
- **Port:** \`8080\`
- **Tests:** \`./mvnw test\`

### 3. Frontend
Navigate to the \`frontend/\` directory:
\`\`\`bash
npm install
npm start
\`\`\`
- **Port:** \`4200\`
- **Tests:** \`npm test\` (Runs Vitest)

---

## 📏 Development Conventions

### Backend Guidelines
- **Migrations:** NEVER use \`spring.jpa.hibernate.ddl-auto=update\`. Always use Flyway migrations located in \`src/main/resources/db/migration/\`.
- **Validation:** Use Bean Validation annotations (\`@NotNull\`, \`@NotBlank\`, etc.) in DTOs.
- **Lombok:** Use \`@Data\` for entities/DTOs but be careful with \`@EqualsAndHashCode\` (prefer explicit inclusion of IDs).
- **Exceptions:** Use the \`GlobalExceptionHandler\` to return consistent error responses.

### Frontend Guidelines
- **Change Detection:** The project uses **Zoneless** change detection (\`provideZonelessChangeDetection()\`). Avoid relying on \`zone.js\`.
- **State Management:** Prefer **Signals** (\`signal\`, \`computed\`, \`effect\`) over traditional observables for local component state.
- **Styling:** Use **SCSS** with Angular Material 3 themes. Avoid adding TailwindCSS unless explicitly requested.
- **Components:** Organize features into \`features/\` and shared logic into \`core/\` or \`components/\`.

### Security
- **JWT Secret:** Managed via \`application.properties\`.
- **CORS:** Configured in \`SecurityConfigurations.java\` to allow requests from the frontend port.
- **Tenant Context:** Always ensure queries are scoped to the current user's \`Tenant\`.

---

## 📂 Directory Structure

- \`backend/\`: Spring Boot application.
- \`frontend/\`: Angular application.
- \`.docker/\`: Persistent database data.
- \`summary.md\`: A historical log of the project's evolution.
