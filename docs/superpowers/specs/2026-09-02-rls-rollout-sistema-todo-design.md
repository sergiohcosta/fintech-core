# RLS — rollout pra todas as tabelas de tenant — Design

> Status: rascunho para aprovação.
> Data: 2026-09-02
> Depende de: `ADR-006-rls-postgres-defesa-em-profundidade.md`, PoC em `transactions` (V33,
> branch `feature/rls-transactions-poc`).

## Problem Statement

O PoC provou o mecanismo (`ENABLE`+`FORCE ROW LEVEL SECURITY`+policy+`SET LOCAL app.tenant_id`)
funciona em `transactions`. Mas revelou um risco de **cobertura**, não de mecanismo: o
`TenantRlsAspect` original tinha pointcut enumerado manualmente
(`TransactionService.*` + `InvoiceService.pay`), e um write path real
(`InvoiceService.pay` grava em `transactions` direto pelo repositório) escapou dele — só foi
achado porque um teste de concorrência já existente expôs o erro por acidente.

Estender esse padrão (pointcut enumerado à mão) pra 12 tabelas × N services cada é repetir
esse risco 12 vezes: toda vez que um write path novo aparecer (ou um existente for
refatorado), alguém precisa lembrar de atualizar o pointcut. Isso é exatamente o tipo de
disciplina manual que RLS existe pra **não** depender.

## Correção de rota: por que "mover pro SecurityFilter" não funciona ao pé da letra

A ideia inicial (levantada em conversa) era fazer o `SET LOCAL` uma vez por request, no
`SecurityFilter`, cobrindo tudo que vem depois automaticamente. **Mecanicamente não
funciona**: `SecurityFilter` é um `OncePerRequestFilter` — roda **antes** de qualquer
transação Spring existir (a transação só abre quando o primeiro método `@Transactional` é
invocado, dentro do controller/service). `SET LOCAL` é escopado à transação; rodar no filtro
seria "solto", sem nenhuma transação pra ele valer, e a conexão JDBC que o filtro pegaria (se
pegasse alguma, fora do gerenciamento do Spring) não seria a mesma que a transação de negócio
usa depois.

**A correção que preserva o espírito da ideia** (cobertura automática, sem pointcut manual)
é generalizar o **pointcut do aspect** em vez de generalizar o ponto de execução:

```java
@Around("within(com.fintech.api.service..*) && @annotation(org.springframework.transaction.annotation.Transactional)")
```

Isso intercepta **todo** método `@Transactional` de **todo** service do pacote, presente e
futuro — sem enumerar classe por classe. Um write path novo (`InvoiceService.pay` do achado
#4, ou qualquer service futuro) entra automaticamente, porque o pointcut é sobre a anotação,
não sobre a assinatura.

## Resolução de tenant: duas fontes, nesta ordem

1. **`SecurityContextHolder`** (`SecurityUtils.currentUser()`, tolerante — sem lançar) —
   cobre 100% do tráfego HTTP real (controllers autenticados), sem depender de nenhum
   parâmetro `User` no método. É a fonte natural: `SecurityFilter` já resolve o tenant por
   request.
2. **Fallback: parâmetro `User` nos argumentos do método** (mecanismo já usado no PoC) —
   cobre chamadas de teste (`@SpringBootTest` chamando o service direto, sem HTTP) e qualquer
   código interno que passe `User` explicitamente.
3. **Nenhuma das duas resolve tenant → SET LOCAL não roda, sem exceção.** Método segue sem
   `app.tenant_id` setado nesta transação. Isso é seguro por construção: se o método toca
   alguma tabela com RLS ativo, a policy nega tudo (fail-safe deny) e o erro aparece alto e
   visível (constraint/policy violation) — nunca um vazamento silencioso. Se não toca tabela
   nenhuma com RLS, não faz diferença.

## Exceção conhecida: tenant que ainda não existe no início do método

`TenantRegistrationService.register(TenantRegistrationDTO dto)` cria o `Tenant` e o primeiro
`User` (ADMIN) **na mesma transação** — no início do método não há usuário autenticado (é
endpoint público) nem `User` nos argumentos (só o DTO). O aspect genérico não resolve tenant
aqui, e como certo (`users` vai entrar no rollout) precisa de `app.tenant_id` setado antes do
`INSERT` do primeiro `User`.

**Não é bug do aspect — é esperado.** Tratamento: `SET LOCAL` manual explícito dentro do
método, logo após o tenant ficar disponível e antes do primeiro INSERT que precisa dele —
mesma técnica (`EntityManager`/`Session.doWork`) já usada no aspect, só que inline. **Duas
ocorrências confirmadas por leitura de código** (não uma, como uma primeira leitura desta
spec assumiu):

1. `TenantRegistrationService.register(TenantRegistrationDTO dto)` — cria `Tenant` e o
   primeiro `User` (ADMIN) na mesma transação. `SET LOCAL` logo após
   `tenant = tenantRepository.save(tenant)`, antes de `userRepository.save(adminUser)` e de
   `categorySeeder.seedForTenant(tenant)` (que grava `categories`, rollout #11 — mesma
   transação, mesma correção resolve os dois).
2. `InvitationService.accept(AcceptInviteDTO dto)` — endpoint público, sem `User`
   autenticado nem no argumento. O tenant existe (`invitation.getTenant()`), mas o aspect
   genérico não tem como alcançá-lo (nem SecurityContextHolder nem parâmetro `User`). `SET
   LOCAL` logo após `findValidInvitation(dto.token())`, antes de `userRepository.save(user)`
   e `invitationRepository.save(invitation)`.

Padrão geral pra identificar esses casos: **método `@Transactional` público (endpoint não
autenticado) que grava em tabela com RLS sem receber `User`/tenant já resolvido no
argumento.** Vale conferir os dois outros endpoints públicos de auth
(`TenantController`/`InvitationController`, ver `summary.md` — "Público: POST
/auth/{login,register,accept-invite}") na Task 1 de execução, não só confiar nesta lista.

## Escopo — tabelas (ordem de rollout por risco)

`recurrence_exceptions` fica de fora da primeira rodada — não tem `tenant_id` direto (só via
FK `rule_id`); decisão de denormalizar ou não é separada, ver seção própria.

| Ordem | Tabela | Racional da posição |
|---|---|---|
| 1 | `staged_transactions` | Dado de terceiro (import), maior superfície de exposição nova |
| 2 | `import_batches` | Mesmo pipeline do 1, mesmo risco |
| 3 | `invoices` | Financeiro core, dado de cartão de crédito |
| 4 | `installment_groups` | Financeiro core, ligado a `transactions` já protegida |
| 5 | `accounts` | Financeiro core, referência de praticamente tudo |
| 6 | `credit_card_details` | Dado sensível (dados de cartão), baixo volume de writes |
| 7 | `budget_cycles` | Planejamento, menor superfície de escrita externa |
| 8 | `budget_items` | Idem |
| 9 | `recurrence_rules` | Idem |
| 10 | `users` | Cuidado: exceção de registro (seção acima); baixo volume de writes |
| 11 | `categories` | Baixo risco, hierárquica (herda tenant do pai — já garantido na app) |
| 12 | `invitations` | Baixo volume, TTL curto (convites expiram) |

Uma tabela por PR (mesmo ritmo do PoC): migration + regressão completa + merge, antes de
seguir pra próxima. Não faz sentido paralelizar — cada rollout pode revelar um write path
esquecido (como o achado #4), e descobrir isso uma tabela de cada vez é mais barato que
descobrir 12 de uma vez.

## `recurrence_exceptions` — decisão separada

Sem `tenant_id` direto. Duas opções:
1. **Denormalizar `tenant_id`** (mesmo padrão de `staged_transactions`, migration
   `ADD COLUMN` + backfill via JOIN em `rule_id`) — permite policy direta, consistente com o
   resto do rollout.
2. **Não dar RLS direto** — a tabela só é acessada hoje via JOIN em `recurrence_rules`
   (já protegida), nunca por query solta; risco residual é uma query futura sem JOIN, cenário
   que RLS existe justamente pra pegar. Mais barato agora, mais frágil depois.

Recomendação: opção 1, na mesma leva de `recurrence_rules` (posição 9) — o custo marginal da
coluna é baixo e fecha o buraco de vez.

## Migrations

Próxima livre: V34 (após V33, PoC). Uma migration por tabela
(`V34__staged_transactions_rls.sql`, `V35__import_batches_rls.sql`, ...), mesmo padrão do V33:

```sql
ALTER TABLE <tabela> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <tabela> FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON <tabela>
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
```

Homelab (dev/hmg/prod): confirmado que `FORCE` sozinho basta — os 3 roles
(`fintech_core_{dev,hmg,prod}_user`) são owners sem `BYPASSRLS`, nenhuma mudança de infra
necessária (ver ADR-006, seção "Verificação nos ambientes do homelab").

## `TenantRlsAspect` — mudança de escopo

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/config/TenantRlsAspect.java`

Pointcut passa de enumerado (`TransactionService.*` + `InvoiceService.pay`) pra genérico
(`within(com.fintech.api.service..*) && @annotation(Transactional)`). Resolução de tenant
ganha o passo `SecurityContextHolder`-primeiro (seção acima). Testes existentes que hoje
setam `SET LOCAL` manual porque escrevem via repositório direto (`DashboardAggregatesRepositoryTest`,
`InvoiceServicePaymentConcurrencyTest`, `ImportServiceTest`) continuam precisando — o aspect
genérico só cobre chamadas que passam por um método `@Transactional` de `com.fintech.api.service`,
não repositório cru.

**Risco a observar:** o pointcut genérico intercepta TODO `@Transactional` do pacote,
inclusive services que não tocam nenhuma tabela ainda protegida por RLS — custo é uma
`SET LOCAL` extra por chamada (uma query trivial), desprezível. Nenhum teste deveria quebrar
por isso; se quebrar, é sinal de outro write path fora de `@Transactional` do service, mesmo
padrão dos achados do PoC.

## Task Breakdown (por tabela — repete 12x, ajustando nomes)

1. Migration `V3X__<tabela>_rls.sql`.
2. Teste discriminante (mesmo molde de `TenantRlsAspectTest`): sem `app.tenant_id` → 0
   linhas; com tenant A → nunca vaza tenant B; fluxo real autenticado funciona.
3. Suíte completa — qualquer teste que grave na tabela fora de `@Transactional` de service
   precisa do mesmo tratamento (`SET LOCAL` manual via `TransactionTemplate` ou
   `entityManager`, dependendo se o teste usa `@Transactional` de classe).
4. `database-schema.md` — linha da migration.

## Fora de escopo desta spec

- Generalizar o aspect pra **outros pacotes** além de `com.fintech.api.service` (ex.: se
  algum dia existir lógica de escrita fora de service) — não há caso hoje, não construir
  pra hipótese.
- RLS em `tenants` — é a raiz, não se isola dela mesma.
- Qualquer mudança de contrato de API — RLS é 100% interno, SemVer não afetado.

## Critério de conclusão

- [ ] `TenantRlsAspect` generalizado, suíte completa verde.
- [ ] `TenantRegistrationService.register` e `InvitationService.accept` com `SET LOCAL`
      manual, testados.
- [ ] 12 tabelas com `ENABLE`+`FORCE`+policy, cada uma com teste discriminante próprio.
- [ ] `recurrence_exceptions` — decisão registrada e executada (denormalizar ou não).
- [ ] `database-schema.md` atualizado, uma linha por migration.
