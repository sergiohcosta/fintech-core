# Referência de Contratos de API & Regras de Negócio

> Fonte de verdade para domínio, endpoints e regras implementadas. Spec-Driven Development.
> Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## Segurança

```
Público:   POST /auth/{login,register,accept-invite} · GET /invites/{token} · /openapi.yaml · /swagger-ui/** · /actuator/health
ADMIN:     POST /invites · GET /api/members · PATCH /api/tenant/settings
Demais:    authenticated (JWT obrigatório)
```
**Invariante:** toda query de negócio filtra pelo `tenant` autenticado. Senha só via BCrypt, nunca em DTO. JWT assina `sub = email`. `SecurityFilter` valida em toda requisição.

**Login (`/auth/login`):** resposta genérica (401) tanto para email inexistente quanto para senha incorreta ou usuário inativo (sem enumeração de usuários). Rate limit em memória: 5 tentativas falhas por email a cada 1 minuto → `429 Too Many Requests` (`LoginRateLimiter`). **Chave = email apenas** (#144) — não deriva de `X-Forwarded-For` (controlável pelo cliente sem trusted proxy, permitia bypass rotacionando o header). Mapa com teto (`security.rate-limit.max-keys`, default 100k) + sweep periódico (`@Scheduled`, `@EnableScheduling`) evitam DoS de memória por flood de emails. `User.isEnabled()` reflete o campo `active` — checado no login e em toda requisição autenticada (`SecurityFilter`).

**Export CSV (frontend):** `csvField` (`core/csv.utils.ts`) neutraliza CSV formula injection (#143) — prefixa `'` quando o valor começa com `= + - @` TAB/CR, além do quoting RFC 4180 (aspas para `; " \n \r`). Defesas distintas: quoting protege a estrutura do CSV, o apóstrofo impede a planilha de executar a fórmula.

**Política de senha (registro e aceite de convite):** mínimo 8 e máximo 72 caracteres, com letra maiúscula, minúscula e número (`TenantRegistrationDTO`, `AcceptInviteDTO`).

## Auth & Convites

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/auth/register` | público | Cria Tenant + User(ADMIN); retorna `{ id, name }` (201, **sem JWT** — login em seguida) |
| POST | `/auth/login` | público | Valida credenciais + JWT |
| POST | `/auth/accept-invite` | público | Valida token + cria User(MEMBER) + JWT |
| POST | `/invites` | ADMIN | Cria convite (email + token + expiresAt) |
| GET | `/invites/{token}` | público | Retorna { email, tenantName } |

## Contas (`/api/accounts`)

GET (lista ativas) · POST · GET/{id} (inclui `balance`) · PUT/{id} (PATCH semântico, inclui `creditCardDetails`) · DELETE/{id} (arquiva, `active=false`)

**Liquidez vs Patrimônio** — dois flags distinguem "disponível agora" de "patrimônio total":

| Campo | Pergunta | Default CHECKING/CASH | Default INVESTMENT/CREDIT_CARD |
|-------|----------|----------------------|-------------------------------|
| `countInLiquidBalance` | disponível imediato? | `true` | `false` |
| `countInNetWorth` | integra patrimônio? | `true` | `true` |

- `countInLiquidBalance` → `sumNetLiquidBalanceByTenant()` → `totalAccountBalance` do Dashboard.
- `countInNetWorth` → armazenado, ainda não consumido (futura tela de Patrimônio).
- Frontend auto-ajusta `countInLiquidBalance` ao trocar tipo (override permitido).
- `balance`: `SUM(CASE WHEN type=INCOME THEN amount ELSE -amount END) WHERE account=:account AND status=PAID`. **Exceção `CREDIT_CARD` (#198):** filtra `status=PENDING` em vez de `PAID` — o saldo do cartão é a dívida em aberto (compras ainda não pagas), não o histórico de pagas. Ao pagar a fatura (`InvoiceService.pay`), as transações da fatura viram `PAID` e **saem** do saldo do cartão — o débito real já foi registrado como EXPENSE `PAID` na conta de origem.

## Categorias (`/api/categories`)

GET `?includeArchived=` (árvore) · POST (com `parentId?`) · GET/{id} · PUT/{id} (propaga icon/color a descendentes) · DELETE/{id} · POST/{id}/archive `?targetCategoryId=`

- Soft delete `deleted_at` em cascata na subárvore. `DELETE` → 409 `{ transactionCount }` se subárvore tem transações.
- `archive` com `targetCategoryId`: reassocia transações antes do soft delete.
- Herança: filho sem icon/color herda do pai. Validação anti-circular (descendente não pode virar pai).
- `TransactionResponseDTO`: `categoryArchived` (nome taxado) · `categoryPath` (ex: `"Pets → Ração"`, montado em `@Transactional`) · `categoryIcon`.

## Transações (`/api/transactions`)

GET (filtros) · GET/{id} · POST (1..N, parcelamento gera N) · PUT/{id} (com `propagate`) · DELETE/{id} `?scope=`

**Filtros (opcionais, combináveis):** `invoiceId` · `accountIds` (plural — singular `accountId` é ignorado) · `status` · `type` · `startDate`+`endDate` (juntos ou 400).

**Regra de data (filtro/sort) — `effectiveSortDate`:**
- Parcela de cartão (`installmentGroup != null AND invoice != null`) → `invoice.dueDate`
- Demais (incl. avulsa de cartão) → `transaction.date`
- Sort descendente, computado em memória. Frontend exibe a mesma regra na coluna "Data".

**Criação parcelada** (conta CREDIT_CARD, `totalInstallments=N`):
```
para i=0..N-1:
  invoiceMonth = resolveInvoiceMonth(date, closingDay).plusMonths(i)
  Transaction { date=dataCompra, installmentNumber=i+1, amount=parcela(i), invoice=faturaDoMês(i) }
```
`resolveInvoiceMonth`: `day <= closingDay` → mês corrente; senão → mês seguinte.
`parcela(i)`: `total/N` truncado (DOWN, 2 casas) nas N−1 primeiras; a **última absorve o resíduo** (`total − (N−1)·parcela`) para que `soma(parcelas) == total` exatamente (#136).

**DELETE `?scope=`:** SINGLE · THIS_AND_NEXT (próximas PENDING) · ALL (todas PENDING do grupo). Protege PAID. Retorna `{ deleted, skippedPaid }`.

**Perna de transferência é imutável isoladamente (#138):** `PUT`/`DELETE` numa transação com `transferId != null` → **400** (`BusinessException`). Double-entry é invariante: as pernas nascem juntas (`createTransfer`) e morrem juntas (`DELETE /api/transfers/{transferId}`). Frontend desabilita editar/excluir individual nessas linhas.

**DELETE bloqueia transação vinculada a item do planejamento (incidente prod 2026-08-13):** `budget_items.transaction_id` é FK RESTRICT (sem `ON DELETE`) — excluir sem desvincular estourava `DataIntegrityViolationException` não tratada (500). `TransactionService.delete` agora checa `BudgetItemRepository.findByTransaction` **antes** do delete e bloqueia com **400** (`BusinessException`, mesmo padrão do #138) pedindo para desvincular o item primeiro (`BudgetItemService.unlink`/`unrealize`). Vínculo de `staged_transactions.promoted_transaction_id` (proveniência de import) é tratado diferente — `ON DELETE SET NULL` na FK (V31), já que ali não é estado de negócio, só histórico.

**PUT `propagate: string[]`:** aplica campos às parcelas futuras `PENDING` (`installmentNumber >` atual). PAID nunca revertido.

## Linha do Tempo (`/transactions/timeline`) — só frontend

Visualização alternativa das mesmas transações (consome `GET /api/transactions` — **nenhum endpoint novo**). Três views em tabs: **Calendário** (heatmap mensal), **Lista agrupada** (períodos relativos: Hoje/Ontem/Esta semana/Semana passada/Este mês/Mais antigos) e **Linha horizontal** (marcadores por dia-efetivo, colisão agrupada por data).

- **Filtros independentes** da lista principal, persistidos em `localStorage` (`fintech.timeline.filters`); `description` é filtro client-side e **nunca** é persistida.
- Reusa a regra `effectiveSortDate` do backend (parcela de cartão → `invoiceDueDate`; demais → `date`) — replicada em `timeline-shared.ts`.
- **"Ver lista"** navega para `/transactions` passando os filtros via `queryParams` (`accountIds,status,type,startDate,endDate,description`); a lista os aplica em `TransactionList.mergeFiltersFromQueryParams` (queryParams **vencem** o `localStorage`).
- Rota registrada **antes** de `transactions/:id` (senão `:id` capturaria a string `"timeline"`).
- Lógica pura testável sem `TestBed` (`*-utils.ts`); o shell é coberto por spec com `overrideComponent()`. Specs de componente exigem `ng test` (não `npx vitest` cru). Spec/design: `docs/superpowers/specs/2026-06-23-transaction-timeline-design.md`.

## Recorrência (`/api/recurrence-rules`) — Motor de Recorrência (núcleo)

GET (lista ativas) · POST (valida RRULE) · GET/{id} · PATCH/{id} (`description`+`baseAmount`) · DELETE/{id} (cancela: `status=CANCELLED`) · PATCH/{id}/reactivate (reativa: `CANCELLED→ACTIVE`) · POST/{id}/occurrences/{date}/confirm · POST/{id}/occurrences/{date}/skip.

**Regra vs. Transação.** A `RecurrenceRule` é a definição atemporal (string RRULE / RFC 5545, expandida pela lib `org.dmfs:lib-recur`). A `Transaction` é o fato imutável, gravado **só** após confirmação. Nada é materializado antecipadamente.

**Projeção on-the-fly (`RecurrenceProjectionService`):** `fantasma(janela) = expand(rrule) − {ocorrências já materializadas} − {EXDATE}`, keyed pela data da ocorrência. Sempre recebe janela (`[from,to]`) — nunca expande "infinito".

**`GET /api/transactions?includeProjected=true`:** mescla reais + fantasmas no período (default `false`, retrocompatível). Fantasma: `projected=true`, `id=null`, status `PENDING`, `recurrenceRuleId`+`occurrenceDate` preenchidos. Ordenação compartilha a regra `effectiveSortDate`. Filtro por `invoiceId` **não** projeta.

**Confirmar:** materializa a ocorrência reusando o caminho de criação de transação (`materializeFromRule` → se cartão, fatura resolvida por `resolveInvoiceMonth`/`getOrCreate`). Body opcional `{amount?, date?}` (override — ajuste pontual; a regra segue projetando o `baseAmount`). Índice único parcial `(recurrence_rule_id, recurrence_occurrence)` + guard → **409** ao confirmar a mesma ocorrência 2x.
- **Validação de slot (#146):** só confirma/pula regra `ACTIVE` (senão **422**), com `occurrence` ∈ expansão da RRULE no mês (`RecurrenceProjectionService.occursOn`); confirmar exige `occurrence` ∉ EXDATE. Sem isso, confirmar um não-slot convivia com o fantasma real → pagamento 2×.
- **Vínculo automático ao planejamento (#140):** após materializar, `RecurrenceRuleService.confirmOccurrence` chama `BudgetItemService.linkRecurringOccurrence` — vincula a transação ao item RECURRING PENDENTE do ciclo aberto (se houver), pelo caminho unificado do #141. Sem isso, a transação apareceria como avulsa e o resumo contaria item planejado + avulsa (dupla contagem). Orquestrado no planejamento — `TransactionService` não conhece o domínio de budget.

**Pular:** grava EXDATE em `recurrence_exceptions` (idempotente). A fantasma some no mês pulado e volta no seguinte. Valida slot/status como o confirmar (#146).

**RRULE — subconjunto suportado:** `FREQ=MONTHLY|YEARLY`, `INTERVAL`, `BYMONTHDAY` (1..31 e `-1`=último dia), `UNTIL`, `COUNT`. `@ValidRrule` rejeita o resto (`BYDAY`/`BYSETPOS`/`BYWEEKNO`/`BYYEARDAY`/`BYHOUR`/`BYMINUTE`, e `FREQ` diário/semanal) → **400**. "Fim do mês" = `BYMONTHDAY=-1` (resolve 28/29 fev, 30 abr nativamente). Validação varre as chaves do rrule (o parser lax do lib-recur descarta partes inválidas no contexto).

**Fora do núcleo (sub-projetos futuros):** pausa/retomada, edição "desta em diante", capping não-padrão 31→28, detach formal (#3); fantasma na timeline + simulador "E se...?" (#4). Parcelamento de cartão **permanece** em `InstallmentGroup`+`Invoice`. Spec: `docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md`.

## Transferências (`/api/transfers`)

POST (cria par EXPENSE origem + INCOME destino) · DELETE/{transferId} (remove ambos).
**Double-entry:** 2 Transactions com mesmo `transferId`. Não há entidade Transfer.

## Faturas (`/api/invoices`)

GET `?accountId=` · GET/{id} · POST/{id}/close · POST/{id}/pay `{ sourceAccountId, paymentDate? }`

**`totalAmount` (lista e detalhe) — líquido, não bruto:** `SUM(CASE WHEN type=EXPENSE THEN amount ELSE -amount END) WHERE status<>CANCELLED` (`sumAmountByInvoice`/`findByAccountWithTotals`). INCOME (estorno/reembolso) abate o total em vez de somar — mesma convenção de sinal do dashboard/saldo de conta. É o valor usado como base do pagamento em `pay()`.

**Ciclo:** `OPEN → [close] CLOSED → [pay] PAID`
- **close:** só muda status. Novas transações ainda aceitas (cobranças atrasadas).
- **pay** (`@Transactional` única): **claim atômico** `UPDATE ... SET status=PAID WHERE id=:id AND status=CLOSED` (`markAsPaidIfClosed`) ANTES de qualquer efeito — 0 linhas afetadas → `IllegalStateException` (pagamento concorrente já venceu, #139). Só o vencedor: PENDING→PAID via `@Modifying` batch; se total>0 cria EXPENSE na origem (`date=paymentDate` — **#199**: campo opcional do request, sugerido como hoje no modal do frontend; ausência vira `LocalDate.now()` no service; `description="Pagamento fatura {acc} {MM}/{yyyy}"`). Fecha o ciclo de caixa do cartão (`countInLiquidBalance=false`).
- **Validações pay:** origem do tenant (404), origem ≠ CREDIT_CARD (422), fatura CLOSED (422), `paymentDate` não pode ser futuro (**400**, `BusinessException` — #199: o pagamento nasce `PAID` e `totalAccountBalance` não filtra período, então data futura rebaixaria hoje um caixa que ainda não saiu). Ordem: validações → claim atômico → efeitos.
- **Lazy create** (`getOrCreate`): automático na 1ª transação do período. `UNIQUE(account, year, month)`. Race condition resolvida com `@Transactional(REQUIRES_NEW)` + retry (ADR-001 #83).
- **dueDate:** `dueDay >= closingDay` → mesmo mês; senão → mês seguinte. Dia capado ao último do mês (`min(dia, lengthOfMonth)`) — closingDay/dueDay=31 em fevereiro não estoura `DateTimeException` (#137).

## Grupos de Parcelamento (`/api/installment-groups`)

GET · GET/{id} · DELETE/{id} (remove PENDING do grupo) · PATCH/{id} (metadados).

## Dashboard (`/api/dashboard/summary?period=YYYY-MM`)

Resposta: `{ period, income, expense, balance, transactionCount, totalAccountBalance }` (todos excluindo CANCELLED).

**Regra de período — LEFT JOIN obrigatório:**
```sql
LEFT JOIN t.invoice inv
WHERE (inv IS NOT NULL AND inv.dueDate BETWEEN :start AND :end)
   OR (inv IS NULL    AND t.date       BETWEEN :start AND :end)
AND t.status <> CANCELLED
```
`t.invoice.dueDate` direto no WHERE gera INNER JOIN implícito no Hibernate e exclui transações sem fatura.

`income`/`expense`/`transactionCount` **excluem** transferências (`transferId IS NULL`) e pagamentos de fatura (`paidInvoice IS NULL`) — senão a transferência infla os dois lados e o pagamento de cartão conta a despesa 2× (compra no mês do `dueDate` + pagamento no mês do débito) (#145).

`totalAccountBalance`: `SUM(±amount) WHERE status=PAID AND account.countInLiquidBalance=true AND account.active=true` (sem filtro de período). Exclui contas arquivadas (#151) — consistente com o `openingBalance` do ciclo. **Não** exclui o pagamento de fatura: é saída real de caixa e deve rebaixar o saldo.

## Planejamento Mensal (`/api/{budget-cycles,budget-items}` + `PATCH /api/tenant/settings`)

- `BudgetCycle`: datas calculadas por `startDay` (1 → mês calendário; N → dia N do mês anterior até N-1 do atual). Ao abrir, popula itens recorrentes via `RecurrenceProjectionService` (projeção on-the-fly das `RecurrenceRule` ativas) e parcelas de cartão do período.
- `BudgetItem`: criação, update, link/unlink a transações. **`link` e `realize` compartilham os mesmos guards (#141):** ciclo OPEN, item PENDING, transação em no máximo um item (qualquer ciclo), compatibilidade de tipo e sync de `amount` com a transação. Itens `source=RECURRING` carregam `recurrenceRuleId` + `recurrenceOccurrenceDate` para rastreabilidade.
- **`syncInstallments` (aditivo, #152):** reconcilia só os PREVISTOS — remove apenas itens INSTALLMENT `status=PENDING` sem transação vinculada e regenera; itens REALIZED/vinculados são preservados (fato consumado não é apagado numa reconciliação), sem duplicar grupos já cobertos.

**`openingBalance` (ao abrir):** `sumLiquidBalanceByTenant` = caixa líquido PAID **anterior** ao ciclo (`t.date < startDate`, contas `countInLiquidBalance=true`). O corte de data evita dupla contagem: transações dentro do período só entram via realizados/avulsas, nunca no opening.

**Resumo do ciclo (`BudgetSummaryService` — fonte única; o DTO é só mapeador):**
- `currentBalance` = **caixa real agora** = `opening + realizados + avulsas`, contando **só PAID** (realizado = item REALIZED cuja transação está PAID).
- `availableToSpend` = **projeção do que dá pra gastar** = `opening + toda receita − toda despesa` (itens ativos exceto SKIPPED + avulsas), **independente de PAID/PENDING**. Conservadorismo simétrico: receita só ajuda quando lançada; despesa pesa assim que existe no sistema. Equivale a `projectedBalance + (avulsas: receitas − despesas)`.
- `dailyAllowance` = `availableToSpend / dias restantes` (FLOOR 2 casas; 0 se ≤0 ou sem dias; null fora de OPEN).
- `unplannedIncome/Expense` no DTO = **total** das avulsas (PAID+PENDING), para exibição; a lista de avulsas inclui PENDING com badge.
- `BudgetItemResponse.transactionStatus`: status (PAID/PENDING) da transação vinculada — permite ao frontend distinguir realizado-em-caixa de realizado-pendente sem assumir `REALIZED = pago`.
- `BudgetItemResponse.recurrenceRuleId` + `recurrenceOccurrenceDate`: presentes quando `source=RECURRING`; permitem ao frontend navegar para a regra ou exibir o slot canônico.

**Frontend do Planejamento (ciclo atual):**
- Cada card (Receitas, Despesas, Saldo, Disponível) tem ícone de "olho" → modal de composição (fórmula + itens contribuintes), montado de `BudgetSummaryService` no frontend a partir dos dados já carregados.
- Cards atualizam em tempo real após mutações (refresh silencioso do ciclo, sem flash de loading).
- Lista de não planejados: coluna de status (Pago/Pendente), ação "vincular a item planejado" e "criar item planejado" (cria + vincula à transação de origem).
- Aba "Recorrentes" gerencia `RecurrenceRule` diretamente (CRUD completo, incluindo reativação de regras canceladas) — a tabela `recurring_budget_items` foi removida (V21).

## Importação / Extração (`/api/imports`) — Fase 0 (fundação) + Fase 1 (imagem) + Fase 2 (CSV/OFX) + Fase 3 (PDF texto)

Pipeline de extração multi-mídia (roadmap `docs/roadmap-extracao-e-conciliacao.md`). A Fase 0 provou o contrato de staging ponta a ponta (sem extrator real); a Fase 1 ligou o extrator de verdade para imagem; a Fase 2 (fatiada, metade A) generalizou a porta pra N transações por arquivo e somou CSV/OFX; a Fase 3 (fatia 1) somou PDF com texto. Specs: `docs/superpowers/specs/2026-07-24-extracao-fundacao-e-mvp-imagem-design.md`, `docs/superpowers/specs/2026-07-28-extracao-fase2-csv-ofx-design.md` e `docs/superpowers/specs/2026-07-31-extracao-fase3-pdf-texto-design.md`.

**Tipos aceitos e roteamento:** `IMAGE` (JPEG/PNG/GIF/WEBP), `CSV` (genérico, header por sinônimo), `OFX` (1.x SGML ou 2.x XML) e `PDF_TEXT` (PDF com camada de texto — heurística de linha, sem registry de templates). O `ExtractionRouter` escolhe o `TransactionExtractor` por **conteúdo** — nunca pelo `Content-Type` do cliente (mente com frequência: um `.ofx` chega como `application/octet-stream`). Ordem do funil (`@Order`): `OfxExtractor` (10, padrão universal) → `CsvExtractor` (20, genérico por heurística) → `PdfTextExtractor` (30, texto de PDF por heurística de linha) → `VisionExtractor` (`LOWEST_PRECEDENCE`, IA — só entra quando nenhum parser determinístico reconheceu o arquivo). Formato que **nenhum** extrator reconhece → `BusinessException` (400, nenhum batch gravado — não há `sourceType` pra persistir). Extrator que reconheceu mas falhou ao processar → `ExtractionException` → batch `FAILED` (mesmo caminho da Fase 1).

**`PdfTextExtractor` (Fase 3, `pdf_text_v1`, Apache PDFBox):** `supports()` reconhece **qualquer** PDF pelo magic number (`%PDF-`) — a distinção "tem texto ou não" só é possível dentro de `extract()`, depois que o PDFBox abre o documento. **PDF escaneado (sem texto extraível) falha explicitamente** ("PDF parece ser uma imagem digitalizada…") em vez de tentar OCR/IA — o `VisionExtractor` hoje só processa bytes de imagem, não sabe renderizar página de PDF; suporte a PDF escaneado é fatia futura da Fase 3. Reconhecimento de transação por **heurística de linha**: uma linha vira transação só se casar um padrão de **data** (`DD/MM/YYYY`, `DD/MM/YY` ou `YYYY-MM-DD`) **e** um **valor monetário** (`1.234,56`/`1234.56`, sinal e `R$` opcionais) na mesma linha — sem os dois, a linha simplesmente não é transação (não é erro, é ausência de sinal: cabeçalho, rodapé, linha de saldo). `amount`/`transaction_date` confiança `1.0` (padrão bateu); `direction`/`description` confiança `0.7` (inferência por sinal/posição, mesma régua do CSV). Palavra-chave `débito`/`crédito` na linha tem prioridade sobre o sinal do valor. **Registry de templates (Fase 3, fatia 2):** antes da heurística genérica, o `PdfTextExtractor` tenta templates por banco (`PdfBankTemplate`, mesmo padrão de lista de beans ordenada do `VisionModelClient`) — detecção por CNPJ da instituição + rótulo de seção conhecido no texto extraído (nunca por nome de arquivo). Hoje: `ItauFaturaTemplate` (`itau_fatura_v1`, datas sem ano inferidas da data de vencimento, exclui "Compras parceladas - próximas faturas" do mês corrente) e `NubankExtratoTemplate` (`nubank_extrato_v1`, state machine linha a linha, direção pela seção "Total de entradas"/"Total de saídas" corrente). Nenhum template bate → heurística genérica de linha (fatia 1), inalterada. `extractor_used` grava o `templateId()` quando um template processa.

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| POST | `/api/imports` | authenticated | multipart (`file` + `importMode`) + query `force` (default `false`) — roteia por conteúdo (imagem/CSV/OFX), grava batch `EXTRACTED` + staged `PENDING`. Falha de um extrator que reconheceu o arquivo → batch `FAILED` (fallback é o formulário manual); formato não reconhecido → 400; arquivo já importado por este tenant sem `force=true` → 409 |
| PATCH | `/api/imports/{id}/staged/{stagedId}` | authenticated | **(Fase 1)** edita campos de uma staged `PENDING` antes de lançar — grava confiança `1.0` (dado confirmado por humano) e re-deriva `requiresReview` |
| POST | `/api/imports/{id}/staged/{stagedId}/discard` | authenticated | **(Fase 2B)** descarta uma staged `PENDING` (→ `DISCARDED`): a linha não vira `Transaction` e para de bloquear a revisão. Staged não-`PENDING` → 400 |
| POST | `/api/imports/{id}/commit` | authenticated | **(Fase 1)** promove as staged listadas (`items: [{stagedId, accountId, categoryId?}]`) a `Transaction`, reusando o caminho de criação existente; marca cada staged `CONFIRMED` e o batch `COMMITTED` quando não sobra nenhuma `PENDING` |
| POST | `/api/imports/mock` | authenticated | (dev) cria batch a partir de um `NormalizedBatchDTO` mockado — prova o ponta a ponta sem extrator |
| GET | `/api/imports/{id}` | authenticated | detalhe do batch (404 se de outro tenant) |
| GET | `/api/imports/{id}/staged` | authenticated | lista as transações em staging do batch (404 se de outro tenant) |

**Extrator (Fase 1 + Onda "Gemini primário / Ollama fallback"):** `TransactionExtractor` é a porta agnóstica de provider; `VisionExtractor` é a implementação sobre uma **cadeia de providers de visão** por trás da porta `VisionModelClient` (`com.fintech.api.service.imports.vision`) — o extrator não conhece `ChatClient` nem SDK nenhum, só recebe uma `List<VisionModelClient>` ordenada por `@Order` e tenta em sequência. Ordem hoje: **Gemini (`@Order(10)`, primário)** → **Ollama do homelab (`@Order(20)`, fallback)** — `GeminiVisionClient` sobre `spring-ai-starter-model-google-genai` (Google AI Studio, tier free, modelo por env var — IDs de modelo do Gemini têm prazo de validade, assar no código garante quebra futura anunciada) e `OllamaVisionClient` sobre `spring-ai-starter-model-ollama` (`llama3.2-vision`, cabe nos 11GB da GPU do homelab). Ambos sobre Spring AI 2.0.0-M2, com o mesmo prompt fixo (divergir por provider duplicaria manutenção sem evidência de necessidade) e pedindo saída **estruturada e tipada** (`.entity(LlmReceiptExtractionDTO.class)` — um record plano, mais fácil pro modelo de visão preencher que o mapa aninhado do `NormalizedBatchDTO`).

**Política de fallback entre providers:** só falha de **disponibilidade** (429/5xx/timeout/401/403/400 — classificada por `VisionProviderErrorClassifier`, lançada como `VisionProviderUnavailableException`) dispara a tentativa do PRÓXIMO client da lista. Qualquer outra falha (`ExtractionException` de conteúdo — imagem ilegível, extrato multi-transação #193, `amount` implausível) propaga IMEDIATAMENTE, sem tentar o próximo — falha de conteúdo repetida no outro modelo pagaria latência dobrada pra chegar à mesma conclusão e mascararia o `failureReason` de #193. Essa distinção é garantida por construção: o guarda-corpo de plausibilidade só roda depois que um client já "venceu" (retornou sem lançar), então é fisicamente impossível ele disparar fallback. `401`/`403` cai pro próximo provider mas loga **ERROR** (não WARN) — degradar é melhor UX que falhar, mas silenciar faria o provider primário morrer sem ninguém notar. Se **todos** os providers configurados falharem por disponibilidade, o motivo do **último** erro vira o texto da `ExtractionException` (nunca a mensagem crua do SDK do provider). Gemini sem `GEMINI_API_KEY`: o bean simplesmente não existe (`@ConditionalOnExpression`, não `@ConditionalOnProperty` — a property tem default vazio `${GEMINI_API_KEY:}` para o autoconfigure não estourar, então `@ConditionalOnProperty` sem `havingValue` bateria `true` mesmo sem chave) — clone novo do repositório, CI e suíte rodam sem segredo, com Ollama assumindo sozinho.

`amount` ausente ou ≤0 derruba a extração (`ExtractionException` → batch `FAILED`); data ilegível não derruba (confiança zerada, usuário completa na revisão). Mapeamento de direção: `debit`→`EXPENSE`, `credit`→`INCOME` (default `debit` se irreconhecível). `requires_review` **nunca** é decidido pelo extrator — quem deriva por threshold é sempre o `ImportService` (mesma regra da Fase 0).

**Proveniência estruturada (V28/V29):** além do `extractor_used` legado (string, ex. `vision_gemini_gemini-2.5-flash`), o batch grava colunas consultáveis: `extractor_provider`, `extractor_model`, `extraction_latency_ms`, `fallback_from` e `fallback_reason`. `fallback_from IS NULL` já responde sozinho "houve fallback?" (nenhum booleano redundante que pudesse divergir); quando não-nulo, identifica o **primeiro** provider da lista que falhou por disponibilidade antes do vencedor. Quem **mede** (latência, provider/modelo vencedor, fallback) é o `VisionExtractor`; quem **grava** é o `ImportService` — a fronteira "extrator não toca banco" não muda. Dado é operacional (consulta é SQL, `GROUP BY provider`/`WHERE fallback_from IS NOT NULL`) — **não exposto na API** nesta entrega (mostrar "IA local vs. gerenciada" ao usuário é UX, escopo futuro).

**Nota de privacidade:** com Gemini como provider primário, a imagem do comprovante do usuário passa a poder sair do homelab e ir para a API do Google (Gemini/Google AI Studio) antes de cair no fallback local. É decisão consciente de custo/qualidade (tier free do Gemini vs. hardware próprio), não efeito colateral — vale considerar ao decidir se um tenant específico exige processamento 100% local (nesse caso, hoje, a única alavanca é não popular `GEMINI_API_KEY` no ambiente, o que desativa o Gemini globalmente e deixa só o Ollama).

**Extração multi-transação por imagem única — print de extrato (#194, spec `2026-08-13-extracao-multi-transacao-imagem-design.md`):** `VisionExtractor` faz até DUAS chamadas ao MESMO client vencedor por imagem. A 1ª pede sempre `LlmReceiptExtractionDTO` (schema de comprovante, inalterado desde a Fase 1 — zero risco de regressão nos 95% de precisão já calibrados). Quando `multipleTransactionsDetected=true` (a mesma flag que o #193 usava para recusar), uma 2ª chamada pede `LlmStatementExtractionDTO` (lista de linhas + `declaredTotalDebits`/`declaredTotalCredits` opcionais) — o Spring AI vincula o JSON Schema de saída ANTES da chamada, então os dois formatos não cabem numa chamada só. Guard-rails do caminho de extrato: 1..60 linhas (`import.vision.statement.max-lines`), `maxOutputTokens` explícito só nesta chamada (`import.vision.statement.max-output-tokens`, default 4096 — gap real que não existia antes: nenhum client de visão tinha teto de saída), `amount` negativo normaliza pro valor absoluto com confiança zerada (nunca descarta a linha). Reconciliação soma×total (`Σ debit` vs. `declaredTotalDebits`, idem credit) é só LOG (`WARN`) quando o total foi declarado e diverge além de `max(0.02, 1%)` — nunca descarta linha, nunca é gate. Falha de disponibilidade NESTA 2ª chamada não tenta outro provider (trocar de modelo no meio da extração misturaria leituras da mesma imagem). `extractorUsed` ganha o sufixo `_statement` (ex. `vision_statement_gemini_gemini-2.5-flash`) — distingue o volume/custo deste caminho e é o marcador para revisitar o gate de confiança no futuro.

**Substitui o guard-rail do #193** (que recusava com `ExtractionException`) — não convive em paralelo: uma imagem multi-transação nunca mais falha só por ser multi-transação. Staged rollout: TODA linha do caminho de extrato sai com `requires_review=true` incondicional (zero dado de produção sobre acurácia do modelo em lista ainda). Só tem efeito porque `ImportService.createBatch` passou a ler `tx.requiresReview()` como **piso, nunca teto** (`Boolean.TRUE.equals(tx.requiresReview()) || deriveRequiresReview(...)`) — os demais extratores (CSV/OFX/comprovante) mandam `null` nesse campo, então o `OR` cai inteiro no cálculo por confiança de sempre, comportamento idêntico ao de antes do #194.

**`failureReason` no batch (V25, #193):** batch `FAILED` carrega o motivo legível em PT-BR — causas distintas pedem ações distintas ("extrato com mais lançamentos que o suportado" ≠ "imagem ilegível"), e recusar sem dizer por quê é meio erro silencioso. Só a mensagem da `ExtractionException` (redigida por nós) chega ao usuário; qualquer outra exceção vira texto genérico — mensagem de infra (host, driver, stack) nunca cruza a borda da API. Frontend exibe no card de falha, com fallback local para batch pré-V25.

**Commit (`POST .../commit`):** por item, valida sanidade dos valores **atuais** (originais ou já editados via PATCH) — `amount >= 0.01` e `transaction_date` parseável, senão 400 (`BusinessException`). Cria a `Transaction` reusando `TransactionService.create` (não reimplementa regra de fatura/parcela) com `status=null` → aplica o default `PENDING`, igual a um lançamento manual. Staged já não-`PENDING` (reenvio) → 400.

**Reconhecimento de parcelamento na importação (Itaú, #import-itau-parcelamento):** staged com `installment_number=1` e `installment_total>1` no `fields` (só o `ItauFaturaTemplate` preenche hoje) commita como parcelamento COMPLETO — `amount × installmentTotal` vira o total e `TransactionService.create` recebe `totalInstallments`, criando o `InstallmentGroup` + N transações pelo caminho já existente (mesma regra de resíduo do #136). Teto de sanidade `installmentTotal <= 36` evita que um falso positivo do marcador de parcela (regex do template) multiplique o valor por dezenas. Parcela `>1/N` sem grupo correspondente no sistema commita avulsa (comportamento inalterado) — casar contra um grupo já existente é reconciliação de verdade, fora de escopo (roadmap Fase 4/5). Frontend (revisão em lote) particiona a tabela em seções "Avulsas"/"Parceladas" pela presença desses campos, com aviso na parcela `>1/N`.

**Seções "produtos e serviços" e "internacional" (Itaú, spec `2026-08-10-itau-internacional-produtos-servicos`):** além de "Lançamentos: compras e saques", o `ItauFaturaTemplate` também extrai "Lançamentos: produtos e serviços" por transação (taxas do cartão, ex. "Anuidade Diferenciada") — mas **nunca** popula `installment_number`/`installment_total`, mesmo quando a linha traz o marcador `NN/NN` de parcela: no corpus real essa "parcela" é uma taxa recorrente cobrada e estornada quase no mês seguinte, não uma compra parcelada, e criar um `InstallmentGroup` completo projetaria cobranças futuras fantasmas. Também extrai "Lançamentos internacionais", mas **consolidado** — no máximo 1 transação sintética por fatura (não por lançamento), lida direto da linha de subtotal já impressa (que soma tudo certo, incluindo IOF) — o formato por linha individual é frágil (amostra pequena do corpus, 9/21 faturas).

**Fatura-alvo do documento ancora o commit (V30, spec `2026-08-09-itau-fatura-ancora-por-documento`):** quando o batch tem fatura-alvo (`import_batches.target_invoice_reference_year/month`, hoje só populado pelo `ItauFaturaTemplate` a partir do vencimento impresso), o commit **não** recalcula por `resolveInvoiceMonth(dto.date(), closingDay)` — a transação ANCORA direto na fatura que o próprio documento representa, via `TransactionService.create(dto, user, YearMonth)`. Vale para TODA linha do documento (avulsa, parcela 1 ou parcela `>1/N` em andamento): o Itaú já decidiu em que fatura aquela linha caiu, e recalcular pelo `closingDay` configurado na conta reintroduz a mesma fragilidade que causava o roteamento errado de parcelas em andamento (data de compra antiga). Batches sem fatura-alvo (CSV/OFX/imagem/heurística genérica) seguem no caminho existente, sem mudança de comportamento.

**Descarte (`POST .../staged/{stagedId}/discard`, Fase 2 metade B):** transição de estado (`PENDING → DISCARDED`), por isso verbo como sub-recurso e não um `status` no `PATCH` (que é, por contrato, correção de campos). Sem corpo; 200 com a staged atualizada. O gate "não sobra nenhuma staged `PENDING` no batch ⇒ batch `COMMITTED`" é um método privado ÚNICO do `ImportService`, chamado tanto pelo `commit()` quanto pelo `discardStaged()` — descartar a última pendente sem nunca chamar `commit` deixaria o batch preso em `EXTRACTED` para sempre. Não é idempotente: descartar de novo → 400.

**Staging separado, não `DRAFT` em `transactions`:** o dado extraído é probabilístico (carrega `confidence`, `requires_review`) e nasce em `import_batches` + `staged_transactions`, sendo **promovido** a `Transaction` só no commit (Fase 1). `transactions` continua sendo, linha a linha, apenas fato confirmado — nenhuma query de negócio existente precisa passar a filtrar dado sujo.

**Isolamento de tenant (invariante nº1):** `ImportService` recebe `User` e filtra `user.getTenant()`. `staged_transactions.tenant_id` é **denormalizado** (também está no batch) — defesa nº1: toda leitura filtra o tenant direto na linha, sem depender de JOIN em `import_batches`. Recurso de outro tenant → **404** (não confirma existência).

**`requires_review` é DERIVADO no código, nunca pelo modelo:** `deriveRequiresReview` marca `true` se `overallConfidence < import.review.overall-threshold` (0.90) **ou** se a confiança do campo `amount` < `import.review.amount-threshold` (0.95); ausência de confiança conta como duvidoso. O produto controla a régua por properties, sem retreinar nada. O campo `requiresReview` do DTO de entrada é ignorado.

**`fields JSONB` (`@JdbcTypeCode(SqlTypes.JSON)`):** `{value, confidence}` por campo (amount, currency, transaction_date, posting_date, description, direction, payment_method, installment_number, installment_total), keyed pelo nome — mapa flexível (Hibernate 6 nativo, zero dependência nova). `posting_date DATE` nullable também entrou em `transactions` (V23), ainda não consumido (Fase 5). `installment_number`/`installment_total` (confiança `1.0`) só são gravados pelo `ItauFaturaTemplate` quando a linha traz o marcador `NN/NN` de parcela.

**Seed (V24):** 1 batch COMMITTED + 2 staged CONFIRMED com `promoted_transaction_id` → transações do V13.

**`OfxExtractor` (Fase 2, `ofx_parser_v1`):** parser próprio enxuto — não uma lib de banking completa (`ofx4j` traria um cliente inteiro pra usar 5%). Truque que cobre SGML 1.x (tags sem fechamento) E XML 2.x com a MESMA regex (`<TAG>\s*([^<\r\n]+)`): o valor de uma tag termina no próximo `<` (fecha em XML) OU em quebra de linha (SGML). Mapeamento: `amount` = `|TRNAMT|` (conf. `1.0` — sinal é dado, não inferência), `direction` do sinal de `TRNAMT`, `transaction_date` de `DTPOSTED` (aceita `YYYYMMDD` e `YYYYMMDDHHMMSS[.SSS][TZ]`, só os 8 primeiros dígitos), `description` = `MEMO` ou `NAME` na ausência, `external_id` = `FITID` (autoridade de dedup), `currency` = `CURDEF` do envelope.

**`CsvExtractor` (Fase 2, `csv_generic_v1`, `commons-csv`):** sem contrato fixo de colunas — resolve por heurística determinística (nunca IA). `supports()` exige header com ao menos 1 coluna de data e 1 de valor reconhecidas por sinônimo normalizado (sem acento, minúsculo); sem isso, 400 explícito (mandar pra IA é critério de saída da Fase 3). Delimitador (`,`/`;`) detectado tentando parsear com cada candidato e aceitando o de largura de coluna consistente — resolve nativamente aspas com o outro delimitador dentro do campo. **Duas passadas de parsing, nesta ordem:** (1) **arquivo inteiro** — único jeito de honrar RFC 4180 por completo, em especial campo entre aspas com **quebra de linha dentro** (descrição multilinha é UM registro, não N); aceito só se **todas** as linhas de dado batem a largura do header, porque uma aspa solta faz o parser engolir o resto do arquivo num campo gigante e o resultado passaria como "válido" com largura errada; (2) **linha a linha** (fallback) — quando (1) não fecha, a linha malformada fica **isolada** como "linha ruim" (confiança 0, valor nunca apagado) em vez de derrubar o arquivo inteiro. Charset: BOM UTF-8 → UTF-8; senão UTF-8 estrito; só cai pra ISO-8859-1 se UTF-8 falhar. Decimal pt-BR vs. padrão inferido **por valor** (não por coluna fixa): dois separadores → o último é o decimal; só vírgula → vírgula é decimal.

**Escala de confiança determinística (Fase 2):** `1.0` quando o campo veio de dado com contrato formal (TRNAMT com sinal, header casado por nome) ou foi confirmado por humano; `0.7` quando é inferência (direção pelo sinal do CSV, coluna de descrição escolhida por posição na ausência de sinônimo); `0.0` quando ausente/ilegível — mesma régua da Fase 1 (`clampConfidence` do `VisionExtractor`), agora explícita por camada de certeza em vez de só "confiança do modelo".

**Guarda-corpo central de sanidade (`ImportService`, comum a todos os extratores):** `import.file.max-transactions` (default 500) excedido → `FAILED` antes de montar staged; data fora de `[hoje-10 anos, hoje+1 ano]` ou valor ausente/zero/ilegível → confiança do CAMPO zerada (valor preservado, nunca apagado — usuário corrige na revisão); zero transações com `amount` aproveitável → `FAILED` com motivo. Cada extrator já valida seu próprio formato; esta é a rede de segurança igual para todos, num só lugar.

**Dedup por arquivo (409/`force`):** `sha256` dos bytes calculado **antes** de extrair. Achou batch com o mesmo hash **no mesmo tenant** → 409 (`DuplicateImportException`, corpo com `batchId`/`createdAt`/`filename`) — reimportar sem querer duplicaria um mês inteiro de lançamentos. `?force=true` reimporta mesmo assim. Escopo **por tenant**, não global: o mesmo arquivo pode legitimamente existir em duas famílias diferentes.

**Dedup intra-batch:** `external_id` (FITID do OFX) é autoridade — único por transação segundo o padrão OFX; sem ele (CSV), cai no trio `(data, valor, descrição)`. Segunda ocorrência recebe `duplicateCandidateOf` = id da primeira; **nenhuma linha é descartada** — o usuário decide o que fazer na revisão. Frontend exibe badge "Possível duplicata".

**`import_batches.source_hash`/`source_filename` (V26):** chave de dedup e proveniência. Nulável — batches de mock/legado (pré-Fase 2) seguem sem tocar.

**Seed CSV (V27):** 1 batch `EXTRACTED` + 3 staged `PENDING` — a 3ª com `duplicate_candidate_of` preenchido (mesma data/valor/descrição da 1ª), ilustrando o badge de duplicata na tela de revisão.

## Logging Estruturado (MDC)

| Chave | Quando | Valor |
|-------|--------|-------|
| `requestId` | toda req | UUID (`RequestIdFilter`) + header `X-Request-ID` |
| `userId` / `tenantId` | pós-JWT | UUID do usuário/tenant (`SecurityFilter`) |

Dev: console legível. Prod: JSON `logstash` (`application-prod.properties`).

| Camada | Log |
|--------|-----|
| `SecurityFilter` | WARN em token inválido |
| `GlobalExceptionHandler` | ERROR + stack só em 5xx |
| `Service` | INFO em transições de estado de negócio (ex: fatura fechada/paga) |
| `Controller` / `RequestIdFilter` | nenhum |

Nunca logar dados sensíveis (senha, JWT, CPF). `tenantId`/`userId` já estão no MDC.

## Frontend — Padrões

- **Estado:** `signal/computed/effect`. Bridge com FormControl: `toSignal(control.valueChanges, { initialValue })` — `computed()` não reage a `FormControl.value` direto.
- **Reatividade segura:** `untracked()` ao chamar loaders dentro de handlers que leem signals (evita loop).
- **Tabelas agrupadas:** `mat-table` única com múltiplos `*matRowDef` + `when` predicates (`period-header`/`invoice-header`); primeiro `true` vence; `[attr.colspan]` para linha full-width.
- **Testes:** lógica pura em arquivos sem imports Angular (ex: `transaction-list.utils.ts`, `amount-math.ts`, `installment-preview.ts`, `transaction-form.utils.ts`) — testável no Vitest sem `TestBed`. Parsing de valor (ponto-decimal e pt-BR) e formatação de data local (sem UTC) vivem em `transaction-form.utils.ts` (#148).

## Armadilhas Conhecidas (Codegen)

- `auth/auth.service.ts` é regenerado pelo Orval — deletar manualmente antes de usar.
- Sem `required:` em schemas de resposta → Orval gera campos opcionais → `!` assertions.
- `springdoc` deve ser `2.8.9` (incompatível com 2.6.0 no Spring Boot 4.0.1).
