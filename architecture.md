# Arquitetura

## Backend — Camadas

`Controller → Service → Repository`. Entidade JPA nunca exposta — DTO em todas as bordas.

```
domain/      → account/, category/, installment/, invitation/, invoice/,
               tenant/, transaction/, user/, budget/, enums/
dto/         → account/, category/, dashboard/, installment/, invoice/,
               transaction/, transfer/, budget/
controller/  → um por domínio (thin — delega ao service)
service/     → um por domínio (regras de negócio + @Transactional)
repository/  → um por entidade (JPQL custom quando necessário)
config/      → SecurityConfigurations, OpenApiConfig, RequestIdFilter, SecurityFilter
exception/   → GlobalExceptionHandler, EntityNotFoundException
```

**Regras:**
- Controllers finos: delegam ao service imediatamente.
- Services donos da lógica e dos limites transacionais.
- Repositories com JPQL custom; lógica complexa fica no service.
- **Anti-pattern evitado:** services nunca lançam exceções de infra (`jakarta.persistence.EntityNotFoundException`) — sempre relançar via `com.fintech.api.exception.EntityNotFoundException` para o `GlobalExceptionHandler` mapear corretamente.
- Migrations imutáveis: correção sempre via nova versão.

## Frontend — Feature-Based

Módulos lazy-loaded por feature. Cada feature contém seus componentes, services e rotas. Código compartilhado em `core/` (services, guards, interceptors, API client gerado) e `components/`.

**Regras:**
- Features autocontidas; comunicação cross-feature via services em `core/`.
- Funções de lógica pura em arquivos sem imports Angular (testáveis no Vitest sem `TestBed`).
- Validação anti-circular em estruturas hierárquicas (ex: categorias pai/filho).
