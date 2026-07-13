# Fase 1 — Cluster A: aritmética e concorrência de dinheiro

> Campanha de saneamento (auditoria 2026-07). Escopo desta fase: **#136, #137, #139**.
> #135 já foi corrigida no `develop` (commit `270b95e`). Skill:
> `fintech-core-bug-backlog-campaign`.

## Contexto

Os três bugs vivem na tríade `InvoiceService` / `TransactionService` /
`TransactionRepository` — por isso saem numa spec/worktree única (mexer nos mesmos
arquivos em PRs separados geraria conflito de merge e retrabalho). Todos causam
divergência financeira direta: crash na criação de fatura, centavos perdidos/criados
em parcelamento, e pagamento duplicado sob concorrência.

Regra da campanha: **cada bug entra com um teste que FALHA no código atual** (reprodução),
vira regressão permanente após o fix. Baseline verde confirmado antes de abrir a fase
(194 testes, 0 falhas).

---

## #137 — `DateTimeException` para closingDay/dueDay > dias do mês (500 em prod)

**Causa-raiz:** `InvoiceService.createNewInvoice` (`InvoiceService.java:77-82`) usa
`.withDayOfMonth(closingDay)` e `.withDayOfMonth(dueDay)` sem capar o dia ao tamanho do
mês. Cartão com `closingDay=31` cuja fatura fecha em fevereiro → `withDayOfMonth(31)`
lança `DateTimeException: Invalid date FEBRUARY 31`. Na API isso vira **500** e derruba a
criação da transação inteira (a fatura é criada lazy na 1ª transação do período).

**Reprodução (`InvoiceServiceTest`, unit Mockito):** `createNewInvoice(account, 2026, 1)`
com `closingDay=31` → hoje lança `DateTimeException`. (`referenceMonth=1` → fatura fecha em
`fev/2026`, que tem 28 dias.)

**Solução (opção 1 da campanha — capping local):** helper privado

```java
private static LocalDate atDayCapped(LocalDate base, int day) {
    return base.withDayOfMonth(Math.min(day, base.lengthOfMonth()));
}
```

Semântica: "dia N ou o último do mês, o que vier primeiro" — igual ao `BYMONTHDAY=-1` do
motor de recorrência. Aplicar nos dois pontos (closing e due).

**Cerca:** a comparação `dueDay >= closingDay` (que decide mês corrente vs. seguinte da
`dueDate`) continua sobre os dias **configurados**, não os capados — capar antes de comparar
mudaria o mês da fatura em fevereiro. Capar só na hora de materializar a data.

**Espelho no frontend (defesa em profundidade / consistência de preview):**
`installment-preview.ts` → `calcDueDate` usa `new Date(y, m, dueDay)`, que **não lança**
mas rola para o mês seguinte (Feb 31 → 3 Mar) → label de vencimento errado. Capar `dueDay`
ao último dia do mês da fatura (`new Date(y, m+1, 0).getDate()`). Teste Vitest da util pura.

## #136 — divisão de parcelas perde/ganha centavos

**Causa-raiz:** `TransactionService.create` (`TransactionService.java:137-138`) faz
`dto.amount().divide(N, 2, HALF_EVEN)` e aplica o **mesmo** valor às N parcelas. A soma
diverge do total: `100/3 → 33,33×3 = 99,99` (falta 1 centavo); `1000/7 → 142,86×7 =
1000,02` (sobram 2). O `InstallmentGroup.totalAmount` guarda o valor correto, então
soma(parcelas) ≠ totalAmount — invariante quebrada.

**Reprodução (`TransactionServiceTest`, unit Mockito):** invariante
`soma(parcelas) == dto.amount()` para (100.00, 3) e (1000.00, 7) — ambos falham hoje.

**Solução (opção 1 da campanha — última parcela absorve o resto):**

```java
BigDecimal base = dto.amount().divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
BigDecimal last = dto.amount().subtract(base.multiply(BigDecimal.valueOf(installments - 1L)));
```

As N−1 primeiras recebem `base`; a última recebe `last`. Invariante de soma exato por
construção (`(N-1)*base + last = total`). Para `installments == 1`: `base = amount`,
`last = amount − 0 = amount`. Sem regressão no caso avulso.

**Cerca:** `update` com `propagate:["amount"]` reaplica valor uniforme às parcelas futuras
e pode recriar o desvio — **fora do escopo** desta issue. Se confirmado, anotar no relatório
e sugerir issue nova (não consertar de carona sem spec).

## #139 — pagamento duplicado de fatura (concorrência)

**Causa-raiz:** `InvoiceService.pay` (`InvoiceService.java:148-190`) é read-then-write do
status (`if status != CLOSED` no início, efeitos depois). Nenhuma entidade tem `@Version`
(sem optimistic lock) e o EXPENSE de pagamento não tem constraint única. Dois `pay()`
concorrentes na mesma fatura CLOSED leem ambos CLOSED, ambos criam o EXPENSE → **débito 2×**
na conta de origem.

**Reprodução (`@SpringBootTest`, SEM `@Transactional` — precisa commit real):** N iterações;
cada uma monta fatura CLOSED fresca com 1 transação PENDING; 2 threads chamam `pay()` alinhadas
por `CyclicBarrier`; conta EXPENSEs de pagamento. Hoje ≥1 iteração produz 2 pagamentos. Limpeza
manual em `@AfterEach` (banco dev não pode ficar poluído).

**Solução (opção 1 da campanha — UPDATE condicional + linhas afetadas):** novo método de
repositório

```java
@Modifying
@Query("UPDATE Invoice i SET i.status = :paid WHERE i.id = :id AND i.status = :closed")
int markAsPaidIfClosed(@Param("id") UUID id,
                       @Param("closed") InvoiceStatus closed,
                       @Param("paid") InvoiceStatus paid);
```

Reordenar `pay()`: (1) carregar + fast-fail `status != CLOSED` (mensagem amigável p/ OPEN/PAID);
(2) validar conta de origem (404 / 422 cartão) — **antes** do claim, senão marcaríamos PAID e
depois lançaríamos; (3) `int claimed = markAsPaidIfClosed(id, CLOSED, PAID)` — atômico no banco
em READ_COMMITTED; `claimed == 0` → outro concorrente já venceu → `IllegalStateException` (o
`GlobalExceptionHandler` mapeia p/ 409/422) antes de qualquer efeito; (4) só o vencedor cria o
EXPENSE + batch-update das transações; (5) `invoice.setStatus(PAID)` em memória p/ o `buildDTO`.

**Derivação:** o UPDATE condicional é atômico no banco; o segundo concorrente afeta 0 linhas e
aborta antes de criar o pagamento. Sem migration, sem lock retido — só reordenação (status
primeiro, efeitos depois, tudo na mesma `@Transactional`).

**Regressão determinística adicional (não-flaky, no unit test):** mockar
`markAsPaidIfClosed → 0` ⇒ `pay()` lança e **nunca** chama `transactionRepository.save(payment)`;
mockar `→ 1` ⇒ fluxo normal. Prova a fiação do guard sem depender de timing.

---

## Ordem de execução

1. `#137` (unit repro → fix backend + frontend + Vitest) — isolado, rápido.
2. `#136` (unit repro → fix loop de parcelas) — isolado.
3. `#139` (integração repro → repo method + reorder `pay()` → unit determinístico).

## Critério de pronto (mensurável)

- closingDay=31 não lança em fevereiro; preview espelha o capping.
- `soma(parcelas) == totalAmount` para toda a tabela de casos.
- 2 `pay()` concorrentes → exatamente 1 pagamento; 2º recebe erro de estado.
- Testes de reprodução (agora passando) commitados como regressão.
- `./scripts/test-summary.sh` verde (backend + frontend).

## Dataset

Nenhuma mudança de schema/seed (sem migration nova). `dataset.md` não exige atualização
(regra: só nova tabela/coluna/endpoint dispara update de seed).
