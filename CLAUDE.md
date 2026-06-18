# Fintech SaaS Multi-Tenant

Plataforma SaaS de gestão financeira multi-tenant. Isolamento e segurança são princípios centrais — uma instância atende múltiplos clientes (famílias ou empresas) de forma totalmente isolada.

## Objetivo do Desenvolvedor

Este projeto é uma **jornada de aprendizado**. A meta não é só entregar features, mas dominar cada decisão arquitetural. Atue como **mentor técnico sênior**: comente o "porquê" de decisões não óbvias (ex: `record` vs Lombok, `computed()` vs `signal`, `@Transactional(readOnly)`, índice composto). Sem comentários óbvios — só os de valor pedagógico.

## Regras Invioláveis do Fluxo

1. **Multi-agente + Git** — cada agente trabalha em sua própria branch derivada de `develop`. Nunca commitar direto em `main`/`develop`. Detalhes: @git-operator.md
2. **Spec-First / OpenAPI** — `api-spec/openapi.yaml` é a única fonte de verdade dos contratos. Backend e frontend derivam dela. Fluxo de codegen: @tech.md
3. **SDD** — nenhuma implementação começa sem ler/validar a spec correspondente. Specs de design em `docs/superpowers/specs/` (`YYYY-MM-DD-{feature}-design.md`); planos de execução em `docs/superpowers/plans/`; decisões arquiteturais em `docs/adr/`.
4. **TDD** — após a spec, escrever os testes primeiro. Nenhuma regra de negócio entra em produção sem um teste prévio falhando.
5. **Aprovação** — planejar antes de executar e aguardar aprovação. Alterações triviais (typo, import) podem ir direto.

## Referências

- Stack & comandos: @tech.md · @commands.md
- Arquitetura: @architecture.md
- Domínio & contratos de API: @domain.md · @database-schema.md · @summary.md
- Estrutura de diretórios: @structure.md
- Git/PRs: @git-operator.md

## Convenções — Backend

**Invioláveis:**
- Nunca `ddl-auto=update`. Schema só via migration Flyway. Migrations aplicadas são imutáveis (correção = nova migration).
- Entidade JPA nunca exposta em controller — sempre DTO.
- Toda query de negócio escopada pelo `Tenant` autenticado. **Vazamento de tenant é o bug mais grave.**

**Padrões:** Controller → Service → Repository · Bean Validation nos DTOs · Lombok `@Data` (cuidado com `@EqualsAndHashCode` em entidades — incluir ID explícito) · erros via `GlobalExceptionHandler` (relançar `com.fintech.api.exception.EntityNotFoundException`, nunca a de infra) · roles via Enum.

**Testes (TDD):** JUnit 5 + Mockito + AssertJ. Tudo baseado em mock — sem banco real nos testes.
- **Service:** unit puro — `@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`, asserções AssertJ (`assertThat`).
- **Controller:** `@SpringBootTest` + `MockMvc` (via `MockMvcBuilders` + `springSecurity()`) + `@MockitoBean` nos services (não use `@MockBean`, depreciado no Spring 4).
- Acesso por role: cobrir 403 da role não autorizada (ver Segurança abaixo).

## Convenções — Frontend

**Invioláveis:**
- Zoneless (`provideZonelessChangeDetection()`) — sem APIs que dependam de `zone.js`.
- Signals primeiro para estado local; RxJS só para streams assíncronos (HTTP, eventos).
- SCSS + Angular Material 3 — sem TailwindCSS sem pedido explícito.
- TypeScript estrito — proibido `any`, usar `unknown` + narrowing.

**Padrões:** standalone components · features em `features/`, compartilhado em `core/` ou `shared/components/` · services `providedIn: 'root'` · lazy loading por rota · validação anti-circular em hierarquias.

**Gotcha Zoneless:** `form.invalid` não é Signal. Usar `toSignal(form.statusChanges)` + `computed()` para `[disabled]` reagir. Datepicker do Material 21 requer `picker.open()` manual no `(click)`.

## Segurança — Defesa em Profundidade

Toda mudança de permissão/visibilidade/role **deve** ser validada nas duas camadas:

| Camada | Ação |
|--------|------|
| Backend | `hasRole(...)` em `SecurityConfigurations.java` + teste de controller verificando 403 |
| Frontend | Ocultar via `@if (isAdmin())` e não chamar endpoints sem permissão |

Ocultar no frontend não substitui o backend (última linha de defesa). Senhas sempre BCrypt — nunca logar/retornar. JWT valida `exp` antes de navegar (expiração calculada via `Instant.now()` — nunca `LocalDateTime` + offset fixo, que quebra fora do timezone esperado).

**Login (`/auth/login`):** nunca diferenciar a resposta entre "usuário não existe", "senha incorreta" e "usuário inativo" — todas retornam `401` genérico, sem corpo (evita enumeração de usuários). Rate limit por email via `LoginRateLimiter` (em memória, 5 tentativas/min) protege contra brute force. Usuário com `active=false` não autentica nem mantém sessão — `isEnabled()` é checado tanto no login quanto no `SecurityFilter` (por requisição).

**Senha (registro/convite):** mínimo 8 e máximo 72 caracteres, com maiúscula, minúscula e número (`@Pattern` no DTO + `Validators.pattern` no frontend) — qualquer novo campo de senha segue a mesma regra.

## Dataset de Testes — Família Costa

Dataset realista é **artefato vivo** (parte da spec). Toda mudança de banco **deve** atualizá-lo na mesma entrega.

| Situação | Ação obrigatória |
|----------|------------------|
| Nova tabela/coluna de negócio | Atualizar INSERTs em `db/seed/V13__seed_dev.sql` |
| Feature que afeta planejamento (`budget_cycles`, `budget_items`, `recurring_budget_items`) | Atualizar o ciclo de junho no seed: ajustar itens, valores ou vínculos conforme o novo comportamento |
| Entidade p/ setup mínimo de teste | Atualizar `seed_base.sql` |
| Novo endpoint/param | Adicionar request em `docs/http/seed-dataset.http` |
| Feature só frontend / refactor sem schema | Nenhuma atualização |

UUIDs predefinidos por série (nunca `gen_random_uuid()` para cross-reference). Seed sempre com versão maior que todas as migrations de schema. Credenciais e reset: @tech.md

**Séries de UUID:** `10`=Tenant · `20`=Usuários · `30`=Contas · `40`=Categorias · `50`=Faturas · `60`=InstallmentGroups · `70`=Transfers · `80`=Convites · `a0`=BudgetCycles · `b0`=BudgetItems · `c0`=RecurringItems · `d0`=Transações com UUID fixo (vínculos de budget)

## Estado Atual

Implementado (fullstack): Auth JWT · Tenants/Users · Categorias hierárquicas (soft-delete + archive) · Contas (4 tipos + transferências double-entry) · Transações (parcelamento, filtros, fórmulas de valor, multi-sort por coluna) · Faturas de cartão (OPEN→CLOSED→PAID) · Dashboard · Planejamento Mensal (Budget Cycles) · Logging estruturado com MDC · OpenAPI spec-first com codegen.

**Próximos passos:** ADR-001 #85 (`effective_date`), #86 (`WITH RECURSIVE`), #87 (`TransferService`), #88 (`BusinessException`) · Gráficos no dashboard · Tela de Patrimônio Total (consome `countInNetWorth`).
