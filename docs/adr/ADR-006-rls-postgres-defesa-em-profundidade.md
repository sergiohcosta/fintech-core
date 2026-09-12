# ADR-006: Row-Level Security (RLS) do Postgres como defesa em profundidade multi-tenant

## Status

Aceito — 2026-09-02.

Origem: issue #116. Critério de conclusão da issue (decisão em ADR + PoC numa tabela)
cumprido por este documento + spec
`docs/superpowers/specs/2026-09-02-rls-postgres-poc-design.md`.

## Contexto

O isolamento multi-tenant do fintech-core depende **100%** de filtros explícitos na camada de
aplicação (`findByIdAndTenant`, `WHERE tenant_id = :tenantId` em cada query). O padrão é
seguido com consistência em todo o código, mas é **disciplina, não garantia** — um único
método novo que esqueça o filtro já é suficiente para vazar dado entre tenants. Precedente
real: issue #108. O CLAUDE.md deste projeto chama esse cenário de "o bug mais grave possível
neste projeto".

Verificado em 2026-09-02: nenhuma `POLICY` em nenhuma migration (`grep -rni "POLICY"
backend/src/main/resources/db/migration/` vazio). A camada de aplicação é hoje a **única**
linha de defesa.

Debate estruturado (`/octo:debate`, 2026-09-02, 4 participantes) sobre priorizar Open Finance
(spec pausada) vs. este item concluiu **NO-GO para Open Finance até RLS estar implementado** —
introduzir dado bancário de terceiro sobre uma fundação de isolamento não garantida pelo banco
aumenta o valor do alvo exatamente onde a defesa é mais fraca. RLS passou a ser o próximo item
arquitetural, não mais "tech-debt registrado, sem urgência".

## Decisão

**Adotar RLS do Postgres como camada extra de defesa, começando por um PoC restrito à tabela
`transactions`.** RLS não substitui os filtros da aplicação — os dois convivem (defesa em
profundidade real: se um filtro for esquecido, RLS barra; RLS nunca é a única linha).

### Mecanismo de policy

```sql
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON transactions
  USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

`FORCE ROW LEVEL SECURITY` é obrigatório, não opcional: sem ele, o *owner* da tabela (o
usuário de aplicação do `docker-compose` local e, em produção, o usuário do Neon usado pelo
Spring) **ignora** a policy silenciosamente — RLS existiria no schema sem proteger nada.

### Mecanismo de `SET LOCAL app.tenant_id`

**Aspect em torno de `@Transactional`** (Spring AOP), não interceptor JPA de baixo nível.
Racional: todo acesso a dado de negócio no projeto já passa por método de service anotado
`@Transactional` (convenção estabelecida, Controller → Service → Repository) — o aspect lê o
tenant já resolvido pelo `SecurityFilter` (mesma fonte que hoje popula o MDC) e executa
`SET LOCAL app.tenant_id = '<uuid>'` no início da transação. Mais simples de entender e
debugar que um `StatementInspector` que reescreve SQL; o trade-off aceito é que qualquer
acesso a `transactions` fora de um método `@Transactional` fica sem `app.tenant_id` setado —
mitigado porque isso já seria uma violação do padrão de camadas do projeto
(`fintech-core-architecture-contract`), não um caso legítimo a suportar.

`SET LOCAL` (não `SET`) é obrigatório: escopo de transação evita vazar o tenant setado para a
próxima transação que reusar a mesma conexão do pool (HikariCP).

### Escopo do PoC — só `transactions`

Rollout para as demais tabelas de negócio (`accounts`, `categories`, `budget_items`, etc.) é
**fase 2, fora de escopo deste ADR**. Motivo: provar o mecanismo (aspect + policy + `FORCE`)
numa tabela primeiro é mais barato de reverter que aplicar em todo o schema e descobrir um
problema de mecanismo depois.

## Alternativas avaliadas

1. **Interceptor JPA (`StatementInspector`/`Interceptor`) — rejeitada por ora.** Pega toda
   query, inclusive fora de `@Transactional`, mas é mais opaco: reescreve SQL nos bastidores,
   mais difícil de debugar quando uma query não é filtrada como esperado. Reavaliar se o
   aspect mostrar buracos de cobertura no PoC.
2. **Não adotar RLS, reforçar só a camada de aplicação (ex.: lint/teste estático que barra
   query sem filtro de tenant) — rejeitada.** Reduz a chance do erro, mas continua sendo a
   mesma camada falhando sozinha; não é defesa em **profundidade**, é a mesma defesa mais
   apertada.
3. **RLS em todas as tabelas de uma vez — rejeitada por ora.** Maior blast-radius por PR,
   mistura descoberta de problema de mecanismo com rollout, viola a prática do projeto de
   commits pequenos e narrativos.

## Verificação nos ambientes do homelab (dev/hmg/prod)

Confirmado com a infra do homelab (k3s, ver ADR-004) após a implementação local: cada
ambiente tem seu próprio role (`fintech_core_dev_user`/`_hmg_user`/`_prod_user`), **owner do
próprio banco** — não superuser. Só `postgres` é superuser com `BYPASSRLS`, e a app nunca
conecta como `postgres`. Isolamento entre ambientes é por banco/usuário
(`REVOKE CONNECT ... FROM PUBLIC`), ortogonal ao isolamento de tenant dentro de um mesmo
banco (o problema que este ADR resolve).

Isso muda o achado #1 da execução local: lá, `admin` (docker-compose) é superuser — `FORCE`
sozinho não bypassa isso, só um role sem `SUPERUSER` resolve (daí o `fintech_app` criado só
para local). No homelab, os roles são "apenas" owners — `FORCE ROW LEVEL SECURITY` (já em
V33) é suficiente por si só, sem precisar replicar o split de role. Nenhuma mudança adicional
de infra é necessária para dev/hmg/prod; a migration se aplica e a policy passa a valer no
próximo deploy.

## Consequências

- Toda migration nova em tabela com RLS ativo passa a exigir atenção: `INSERT`/`UPDATE` fora
  de contexto transacional autenticado (ex.: seed Flyway, rodado como superuser/owner) não é
  afetado por `FORCE ROW LEVEL SECURITY` só se o seed rodar como owner — a validar no PoC.
- Testes de integração (`@SpringBootTest` contra Postgres real) ganham um teste discriminante
  novo: query nativa sem filtro de tenant, RLS ativo, deve retornar 0 linhas de outro tenant.
- Não muda contratos de API nem comportamento observável pelo frontend — é defesa interna.
