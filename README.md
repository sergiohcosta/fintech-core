# Fintech Core

Plataforma SaaS de gestão financeira **multi-tenant**, construída com Java 21 + Spring Boot 4 no backend e Angular 21 Zoneless no frontend. Um único sistema atende múltiplos clientes (famílias ou empresas) com isolamento total de dados.

---
## Stack

| Camada      | Tecnologias                                                              |
|-------------|--------------------------------------------------------------------------|
| Backend     | Java 21, Spring Boot 4.0.1, Spring Security, JPA/Hibernate, JWT (Auth0) |
| Frontend    | Angular 21 Zoneless, Angular Material 3, RxJS, Vitest                   |
| Banco       | PostgreSQL 16, Flyway (migrations versionadas), pgAdmin) |

---

## Funcionalidades

- **Multi-Tenancy** — isolamento completo por tenant (UUID), suporte a grupos (famílias, empresas, departamentos, etc)
- **Convites** — fluxo de convite com link tokenizado para onboarding de novos usuários
- **Categorias hierárquicas** — árvore multinível de categorias com validação anti-circular
- **Contas financeiras** — corrente, poupança, cartão de crédito (com bandeira e limite)
- **Transações** — receitas, despesas, parcelamentos, controle de status
- **Planjemento** — planejamento por ciclos
- **Dashboard** — resumo financeiro por período

---