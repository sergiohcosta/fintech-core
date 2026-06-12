# ADR-001: Avaliação Arquitetural — Fintech SaaS Multi-Tenant

**Status:** Aceito (estado atual documentado)  
**Data:** 2026-06-11  
**Escopo:** Backend Spring Boot 4 + Frontend Angular 21 Zoneless  
**Migrations aplicadas:** V1–V9 + V10 (seed dev)

---

## Contexto

Plataforma SaaS multi-tenant em fase ativa de desenvolvimento. Domínio financeiro significativamente complexo: transações parceladas, faturas de cartão de crédito, transferências double-entry, categorias hierárquicas com soft delete, filtros por período/conta/status/tipo, e dataset de testes da Família Costa com 100+ transações reais.

Este documento é uma fotografia arquitetural do projeto — registra o que está sólido, os riscos que crescem com o volume de dados, e as decisões que precisam ser tomadas antes das próximas features.

---

## O que está muito bem feito

### Isolamento multi-tenant — consistente em todo o codebase

Toda query de dados de negócio passa por `findByIdAndTenant(...)` ou equivalente. Não há um único método nos repositories que acesse dados sem escopo de tenant. No `TransactionRepository`, até a query de filtros (`findAllByTenantWithFilters`) inclui `WHERE t.tenant = :tenant` como primeira condição. Isso é o risco mais grave do sistema e está tratado com disciplina.

### JPA/Hibernate sem as armadilhas clássicas

- `FetchType.LAZY` em todos os relacionamentos — sem carregamento desnecessário
- `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` com apenas o ID — evita comparação de proxies Hibernate que causa bugs silenciosos
- `@ToString.Exclude` em relacionamentos bidirecionais — sem `StackOverflowError` no log
- `@Builder.Default` para `status = PENDING` e `installments = 1` — estado padrão garantido pela entidade, não pela camada de cima
- `LEFT JOIN FETCH` explícito no `findAllByTenantWithFilters` e `findAllByTenantAndInvoiceWithDetails` — evita N+1 para category/account/installmentGroup/invoice numa só query

O comentário no `TransactionRepository` explicando por que o `LEFT JOIN` explícito é necessário (bug do INNER JOIN implícito do Hibernate com `t.invoice.dueDate`) é exatamente o tipo de conhecimento que se perde se não for documentado.

### Query de filtros server-side bem projetada

`findAllByTenantWithFilters` implementa 5 filtros opcionais num único JPQL com `LEFT JOIN FETCH`. A abordagem de sentinelas de data (`LocalDate.of(1000,1,1)` / `LocalDate.of(9999,12,31)`) resolve o problema do Hibernate 6 que não consegue inferir o tipo de `? IS NULL` para `LocalDate`. O contador `accountIdCount` resolve o mesmo problema para coleções. A regra de data efetiva (parcelas de cartão → `inv.dueDate`, demais → `t.date`) está corretamente alinhada entre JPQL e o `effectiveSortDate` do serviço.

### Tratamento de erros completo e consistente

`GlobalExceptionHandler` cobre o contrato de API:
- 400 — validação de campos (`@Valid`) e regras de negócio
- 404 — entidade não encontrada
- 409 — conflito de negócio (inclui `transactionCount` para categories)
- 410 — convite já utilizado ou expirado
- 500 — fallback com `log.error` e stack trace

Payload JSON padronizado com `timestamp`, `status`, `message`, `details`.

### Frontend moderno e alinhado com as decisões do backend

- Zoneless com `provideZonelessChangeDetection()` + signals para estado local (`currentUser`, `isAdmin` como `computed`)
- Lazy loading por rota, standalone components sem NgModule
- Código de API gerado por Orval a partir do OpenAPI — elimina uma classe inteira de bugs de contrato
- `formStatusSignal = toSignal(form.statusChanges)` + `formValid = computed(...)` — a solução para CD em Zoneless com formulários reativos está correta e documentada (issue #63)

### Logging estruturado com MDC

`requestId` por requisição, `userId/tenantId` após autenticação. Dev: padrão legível. Prod: JSON para Logstash. Raro de ver em projetos neste estágio — facilita enormemente o diagnóstico de problemas multi-tenant.

### Segurança em duas camadas

Regra inviolável documentada e aplicada: toda restrição de acesso tem proteção em `SecurityConfigurations.java` (ex: `hasRole("ADMIN")` para `/api/members`, `/invites`) **e** ocultação no frontend via `isAdmin()`. O frontend é contornável; o backend é a última linha de defesa.

---

## Riscos identificados — por criticidade

### 🔴 Alta prioridade

#### 1. Race condition em `InvoiceService.getOrCreate`

O padrão `findByX().orElseGet(() -> save(...))` tem uma janela de concorrência: duas requisições para a mesma conta/mês chegam simultaneamente, ambas não encontram a fatura, ambas tentam salvar. O banco tem `UNIQUE (account_id, reference_year, reference_month)` (migration V9) como safety net, mas a `DataIntegrityViolationException` resultante sobe sem tratamento e vira um 500 sem mensagem amigável.

*Impacto real:* baixo no uso da Família Costa, mas se o app escalar ou se o usuário abrir múltiplas abas e lançar transações em paralelo, vai ocorrer.

*Solução de curto prazo:* capturar `DataIntegrityViolationException` no `getOrCreate` e fazer retry com `findByX()` após a falha.  
*Solução robusta:* `INSERT ... ON CONFLICT DO NOTHING RETURNING *` com query nativa, ou `@Lock(LockModeType.PESSIMISTIC_WRITE)` no find.

#### 2. `InvoiceService.listDTOs` e `buildDTO` com N+1

```java
return repository.findByAccount(...)
    .stream()
    .map(this::buildDTO)   // 2 queries por invoice: sumAmountByInvoice + countByInvoice
    .toList();
```

Para um cartão com 24 meses: 1 query de lista + 48 queries individuais. A tela de Faturas vai desacelerar progressivamente com o histórico.

*Solução:* query com projeção que traga `totalAmount` e `transactionCount` em passagem única:
```sql
SELECT i, COALESCE(SUM(t.amount), 0), COUNT(t)
FROM Invoice i
LEFT JOIN Transaction t ON t.invoice = i AND t.status <> 'CANCELLED'
WHERE i.account = :account
GROUP BY i
ORDER BY i.referenceYear DESC, i.referenceMonth DESC
```

#### 3. Listagem de transações sem paginação server-side

`findAllByTenantWithFilters` retorna todas as transações do período/filtro em memória. Com os filtros de período (padrão: mês corrente), o impacto imediato é controlado. Mas:
- Sem filtro de data: carrega **tudo** do tenant (sentinelas 1000–9999)
- Ordenação `effectiveSortDate` acontece em memória — impede `ORDER BY` + `LIMIT/OFFSET` no banco
- A coluna `effective_date` não existe no schema — bloqueio arquitetural para paginação real

*Quando isso vira urgente:* ao implementar exportação, relatórios anuais, ou qualquer tela sem filtro de data obrigatório.

*Solução estrutural:* adicionar coluna `effective_date DATE` em `transactions`, preenchida na escrita com a lógica de `effectiveSortDate`. Uma migration + update em `TransactionService.create/update` + ajuste em `InvoiceService.pay`. A partir daí, `ORDER BY effective_date DESC LIMIT/OFFSET` funciona no banco.

---

### 🟡 Médio prazo

#### 4. `CategoryService` com N+1 na árvore

Os métodos `collectSubtreeIds`, `softDeleteSubtree` e `propagateToDescendants` fazem lazy load de `children` nível a nível. Para uma árvore de profundidade 3 com 5 filhos por nó: ~30 queries. Hoje as árvores são rasas — não é urgente. Mas o `WITH RECURSIVE` do PostgreSQL substituiria toda a recursão Java em uma query:

```sql
WITH RECURSIVE subtree AS (
  SELECT id FROM categories WHERE id = :rootId
  UNION ALL
  SELECT c.id FROM categories c JOIN subtree s ON c.parent_id = s.id
)
SELECT id FROM subtree
```

#### 5. `TransactionService` acumulando responsabilidades

Hoje: 6 dependências (4 repositories + `InvoiceService` + `CreditCardDetailsRepository`). Gerencia transações, transferências, parcelamento e resolução de fatura. Já existe `TransferController` separado — faz sentido extrair `TransferService` também. O `InstallmentGroupService` já existe mas opera sobre o grupo, não sobre a criação das parcelas.

*Sinal de alerta:* quando a tela de Patrimônio Total chegar e precisar de queries sobre `countInNetWorth`, vai ser tentador adicionar mais uma dependência aqui.

#### 6. `IllegalArgumentException` como 400 é broad demais

O handler atual captura `IllegalArgumentException` como erro de negócio (400). O problema: o JDK e bibliotecas internas também lançam `IllegalArgumentException` em situações inesperadas. Se código de infraestrutura jogar essa exception, vai virar um 400 com mensagem interna exposta.

*Solução simples:* criar `BusinessException extends RuntimeException` como contrato explícito de negócio. Usar apenas ela para erros 400 intencionais. Reservar o handler de `IllegalArgumentException` para 500.

*Impacto na migração:* há ~8 `throw new IllegalArgumentException(...)` no código. Seriam substituídos por `throw new BusinessException(...)`.

---

### 🟢 Observações de baixo risco

#### 7. `SecurityConfigurations` usa `@Autowired` em campo

```java
@Autowired
SecurityFilter securityFilter;  // único lugar com field injection no projeto
```

O resto usa `@RequiredArgsConstructor` (construtor). Inconsistente e difícil de testar. Corrigível com `@RequiredArgsConstructor` + `final SecurityFilter securityFilter`.

#### 8. `countInNetWorth` armazenado sem consumidor

O campo existe em `Account` desde a migration V5, mas nenhuma query o consome. Intencional para a futura tela de Patrimônio Total. Quando chegar, as queries precisarão de:
- `SUM(CASE WHEN t.type = INCOME THEN t.amount ELSE -t.amount END)` filtrado por `a.countInNetWorth = true`
- Tratamento especial para `CREDIT_CARD` (saldo é negativo do total não pago)

Documentar isso em `CLAUDE.md` antes de implementar evita surpresas de modelagem.

#### 9. JWT em `localStorage` no frontend

Padrão amplamente usado, mas vulnerável a XSS — qualquer script injetado pode ler o token. Para um SaaS financeiro com dados sensíveis, o caminho mais seguro a longo prazo são cookies `httpOnly` (imunes a XSS). Não é urgente mudar agora — requer mudança coordenada em backend (enviar `Set-Cookie` em vez de `{ token }`) e frontend (remover toda a lógica de `getToken()`/`localStorage`).

---

## Resumo de saúde arquitetural

| Dimensão | Avaliação | Notas |
|---|---|---|
| Isolamento multi-tenant | ✅ Sólido | Consistente em todos os repositories |
| Modelagem de domínio JPA | ✅ Sólido | Lazy, equals/hashCode, builders corretos |
| Segurança de API | ✅ Sólido | JWT stateless, BCrypt, RBAC dupla camada |
| Segurança frontend | ⚠️ Aceitável | localStorage tem risco XSS — low priority |
| Performance queries | ⚠️ Risco crescente | N+1 em faturas; sem paginação server-side |
| Concorrência | 🔴 Ponto cego | Race condition em `getOrCreate` sem handling |
| Tratamento de erros | ✅ Bom | Handler completo; `IllegalArgumentException` broad demais |
| Coesão de serviços | ⚠️ Monitorar | `TransactionService` crescendo |
| Observabilidade | ✅ Ótimo | MDC com requestId/userId/tenantId |
| Evolução de schema | ✅ Sólido | Flyway imutável, sem ddl-auto |
| Contrato de API | ✅ Sólido | OpenAPI spec-first + Orval |
| Testes | ⚠️ A crescer | Testcontainers configurado; cobertura ainda baixa |

---

## Itens de ação priorizados

| Prioridade | Item | Esforço |
|---|---|---|
| 🔴 Alta | Tratar `DataIntegrityViolationException` em `getOrCreate` (retry ou catch) | Pequeno |
| 🔴 Alta | Projetar query agregada para `InvoiceService.listDTOs` (sum + count em GROUP BY) | Médio |
| 🟡 Médio | Adicionar coluna `effective_date` em `transactions` como pré-requisito de paginação | Médio |
| 🟡 Médio | Migrar `collectSubtreeIds` para `WITH RECURSIVE` no PostgreSQL | Pequeno |
| 🟡 Médio | Extrair `TransferService` de `TransactionService` | Pequeno |
| 🟡 Médio | Criar `BusinessException` e migrar os 8 `throw new IllegalArgumentException` | Pequeno |
| 🟢 Baixo | Corrigir `@Autowired` campo em `SecurityConfigurations` | Trivial |
| 🟢 Baixo | Documentar queries futuras de `countInNetWorth` no CLAUDE.md | Trivial |

---

## Pergunta para consolidar

O item 3 (coluna `effective_date`) é o bloqueio arquitetural mais relevante para as próximas features. A tela de Patrimônio Total precisará de queries por período — e vai encontrar exatamente o mesmo problema da listagem atual se a coluna não existir. Vale discutir quando criar essa migration antes de começar a próxima feature grande, para não carregar a mesma dívida técnica para frente.
