# Fintech Core

Plataforma SaaS de gestão financeira **multi-tenant**, construída com Java 21 + Spring Boot 4 no backend e Angular 21 Zoneless no frontend. Um único sistema atende múltiplos clientes (famílias ou empresas) com isolamento total de dados.

---

## Visão Geral

```
┌─────────────────────────────────────────────────────────────┐
│                        Frontend                             │
│            Angular 21 · Zoneless · Signals                  │
│                  Angular Material 3                         │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP + JWT
┌────────────────────────▼────────────────────────────────────┐
│                        Backend                              │
│         Spring Boot 4 · Spring Security · JPA               │
│              Controller → Service → Repository              │
└────────────────────────┬────────────────────────────────────┘
                         │ JPA / JDBC
┌────────────────────────▼────────────────────────────────────┐
│                      PostgreSQL 16                          │
│                  Migrations via Flyway                      │
└─────────────────────────────────────────────────────────────┘
```

Cada **Tenant** é isolado por UUID. Nenhuma query de negócio retorna dados de outro tenant — a maior garantia de segurança do sistema.

---

## Stack

| Camada      | Tecnologias                                                              |
|-------------|--------------------------------------------------------------------------|
| Backend     | Java 21, Spring Boot 4.0.1, Spring Security, JPA/Hibernate, JWT (Auth0) |
| Frontend    | Angular 21 Zoneless, Angular Material 3, RxJS, Vitest                   |
| Banco       | PostgreSQL 16, Flyway (migrations versionadas)                           |
| Infra local | Docker Compose (PostgreSQL + pgAdmin)                                    |

---

## Funcionalidades

- **Multi-Tenancy** — isolamento completo por tenant (UUID), suporte a famílias ou empresas
- **Autenticação JWT stateless** — registro, login, refresh via token
- **Convites** — fluxo de convite com link tokenizado para onboarding de novos usuários
- **Categorias hierárquicas** — árvore multinível de categorias com validação anti-circular
- **Contas financeiras** — corrente, poupança, cartão de crédito (com bandeira e limite)
- **Transações** — receitas, despesas, parcelamentos, controle de status
- **Dashboard** — resumo financeiro por período

---

## Como Rodar

### Pré-requisitos

- Docker e Docker Compose
- Java 21+
- Node.js 20+

### 1. Infraestrutura

```bash
docker compose up -d
```

| Serviço    | URL                                           |
|------------|-----------------------------------------------|
| PostgreSQL | `localhost:5432` · db: `fintech`              |
| pgAdmin    | [http://localhost:5050](http://localhost:5050) |

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run
```

API disponível em `http://localhost:8080`.

```bash
# Rodar testes
./mvnw test

# Verificar saúde
curl http://localhost:8080/actuator/health
```

### 3. Frontend

```bash
cd frontend
npm install
npm start
```

App disponível em [http://localhost:4200](http://localhost:4200).

```bash
# Rodar testes
npm test
```

---

## Estrutura do Projeto

```
fintech-core/
├── backend/
│   └── src/main/java/com/fintech/api/
│       ├── config/          # Security, JWT, CORS
│       ├── controller/      # Endpoints REST
│       ├── domain/          # Entidades JPA
│       ├── dto/             # Contratos de API
│       ├── exception/       # GlobalExceptionHandler
│       ├── repository/      # Spring Data JPA
│       └── service/         # Regras de negócio
│
├── frontend/
│   └── src/app/
│       ├── core/            # Services, interceptors, modelos
│       ├── features/        # Módulos lazy-loaded por feature
│       └── components/      # Componentes compartilhados
│
├── api-spec/                # OpenAPI spec (fonte da verdade)
├── docs/                    # Documentação adicional
└── docker-compose.yml
```

---

## Convenções

- **Commits em PT-BR**, imperativo: `adiciona`, `corrige`, `implementa`
- **Schema via Flyway** — nunca `ddl-auto=update`; toda mudança é uma nova migration
- **Entidades JPA nunca expostas** em controllers — sempre DTOs
- **Signals first** no Angular — RxJS apenas para streams genuinamente assíncronos
- **TypeScript estrito** — proibido `any`; usar `unknown` + narrowing quando necessário
- **UUIDs** em todos os IDs externos (anti-enumeração)

---

## Segurança

- Senhas com `BCrypt` — nunca logadas, nunca retornadas em respostas
- JWT validado via `SecurityFilter` a cada requisição
- CORS restrito à porta 4200 em desenvolvimento
- Toda query de negócio escopada pelo tenant do usuário autenticado

---

## Status

| Feature                              | Status   |
|--------------------------------------|----------|
| Infraestrutura e modelagem inicial   | Concluído |
| Autenticação JWT (fullstack)         | Concluído |
| Gestão de Categorias (hierárquica)   | Concluído |
| Gestão de Contas                     | Concluído |
| Sistema de Convites                  | Concluído |
| Gestão de Transações (fullstack)     | Em andamento |
| Dashboard                            | Em andamento |

---

## Licença

Projeto de uso pessoal / educacional.
