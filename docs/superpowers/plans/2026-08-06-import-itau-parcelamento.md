# Importação Itaú — Reconhecimento de Parcelamento Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parcela `1/N` reconhecida na fatura Itaú vira um `InstallmentGroup` completo (N transações) ao ser lançada, em vez de uma transação avulsa que perde o vínculo de parcelamento. Revisão em lote agrupa visualmente avulsas de parceladas, com aviso quando a parcela não é a primeira (parcelas anteriores não importadas).

**Architecture:** `ItauFaturaTemplate` passa a preservar `installment_number`/`installment_total` como campos extras no `fields` JSONB (hoje descartados ao limpar a descrição). `ImportService.commit()` reusa `TransactionService.create` (já suporta `totalInstallments`) passando `amount × N` quando a parcela é a 1ª — zero código de domínio novo. Frontend agrupa a lista de revisão em duas seções (Avulsas/Parceladas), mesmo padrão de `DisplayRow`/linha de cabeçalho já usado em `transaction-list`.

**Tech Stack:** Java 21 (backend), Angular 21 Zoneless/Signals (frontend), JUnit 5 + AssertJ (`@SpringBootTest` contra Postgres dev), Vitest (lógica pura sem TestBed).

## Global Constraints

- Spec de origem: `docs/superpowers/specs/2026-08-06-import-itau-parcelamento-design.md` — decisões já tomadas, não reabrir.
- Nenhuma migration, nenhuma mudança de contrato REST (campos novos dentro do `fields` JSONB já genericamente exposto). Impacto SemVer: **PATCH**.
- Parcela `1/N`: `totalAmount = valor_da_parcela × N` (aproximação combinada — decisão b da spec, não ler "Compras parceladas").
- Parcela `>1/N`: comita como avulsa, sem tentar casar contra grupo existente (decisão d — fora de escopo, dívida técnica registrada).
- Commits em português, imperativo, sem `Co-Authored-By`.

---

### Task 1: `ItauFaturaTemplate` preserva o metadado de parcela

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Produces: `NormalizedTransactionDTO.fields()` ganha as chaves opcionais `installment_number`/`installment_total` (tipo `Integer`, confiança `1.0`) quando a linha de origem trazia o marcador `NN/MM`. Ausentes (não `null` explícito no mapa) quando a linha não tinha marcador.

- [ ] **Step 1: Escrever os testes do novo comportamento**

Adicionar a `ItauFaturaTemplateTest.java` (mesma fixture `CABECALHO_VENCIMENTO`/`pdfComDuasColunas` já usada pelos testes de `parse()` existentes no arquivo — usar o mesmo padrão):

```java
    @Test
    void parseCapturaNumeroETotalDeParcelaQuandoLinhaTemMarcador() {
        byte[] pdfBytes = pdfComDuasColunas("28/11 Foco Aluguel de Ca04/06 112,67", "");
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("installment_number").value()).isEqualTo(4);
        assertThat(tx.fields().get("installment_total").value()).isEqualTo(6);
        assertThat(tx.fields().get("installment_number").confidence()).isEqualByComparingTo("1.0");
        // Descrição continua limpa — o marcador não sobra nela (comportamento já existente).
        assertThat(tx.fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
    }

    @Test
    void parseNaoGravaCamposDeParcelaQuandoLinhaNaoTemMarcador() {
        byte[] pdfBytes = pdfComDuasColunas("03/02 SUBWAY FAZENDINHA 49,00", "");
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields()).doesNotContainKey("installment_number");
        assertThat(transacoes.get(0).fields()).doesNotContainKey("installment_total");
    }
```

- [ ] **Step 2: Rodar os testes, confirmar que falham do jeito certo**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest#parseCapturaNumeroETotalDeParcelaQuandoLinhaTemMarcador+parseNaoGravaCamposDeParcelaQuandoLinhaNaoTemMarcador`
Expected: FAIL — hoje `fields()` não tem essas chaves em nenhum caso (o primeiro teste falha por ausência, não por valor errado).

- [ ] **Step 3: Implementar**

Trocar a declaração do padrão (linha com `TRAILING_INSTALLMENT_MARKER`):
```java
    private static final Pattern TRAILING_INSTALLMENT_MARKER = Pattern.compile("\\d{2}/\\d{2}\\s*$");
```
por (adiciona grupos de captura):
```java
    private static final Pattern TRAILING_INSTALLMENT_MARKER = Pattern.compile("(\\d{2})/(\\d{2})\\s*$");
```

Trocar o record `TransacaoItau`:
```java
    private record TransacaoItau(LocalDate data, String descricao, BigDecimal valor) {}
```
por:
```java
    private record TransacaoItau(
            LocalDate data, String descricao, BigDecimal valor,
            Integer installmentNumber, Integer installmentTotal) {}
```

Em `parseLinha`, trocar o bloco que remove o marcador:
```java
        // Marcador de parcela (ex. "04/06") pode vir colado ao nome do estabelecimento, sem
        // espaço ("Foco Aluguel de Ca04/06") — removido da descrição, não é uma segunda data.
        String antesDoValor = resto.substring(0, amountMatcher.start()).trim();
        String descricao = TRAILING_INSTALLMENT_MARKER.matcher(antesDoValor).replaceFirst("").trim();
        if (descricao.isEmpty()) {
            return null;
        }

        // Fatura fecha ~1 mês antes do vencimento: lançamento com mês MAIOR que o mês de
        // vencimento pertence ao ano anterior (ex.: vencimento 10/03/2025, lançamento 28/11
        // é 28/11/2024).
        int ano = mes > mesVencimento ? anoVencimento - 1 : anoVencimento;
        LocalDate data = LocalDate.of(ano, mes, dia);
        return new TransacaoItau(data, descricao, valor);
```
por:
```java
        // Marcador de parcela (ex. "04/06") pode vir colado ao nome do estabelecimento, sem
        // espaço ("Foco Aluguel de Ca04/06") — removido da descrição, não é uma segunda data.
        // Capturado ANTES de remover — usado pelo ImportService.commit() pra reconhecer a
        // parcela 1 e criar o parcelamento completo (spec: import-itau-parcelamento).
        String antesDoValor = resto.substring(0, amountMatcher.start()).trim();
        Matcher installmentMatcher = TRAILING_INSTALLMENT_MARKER.matcher(antesDoValor);
        Integer installmentNumber = null;
        Integer installmentTotal = null;
        String descricao;
        if (installmentMatcher.find()) {
            installmentNumber = Integer.parseInt(installmentMatcher.group(1));
            installmentTotal = Integer.parseInt(installmentMatcher.group(2));
            descricao = antesDoValor.substring(0, installmentMatcher.start()).trim();
        } else {
            descricao = antesDoValor;
        }
        if (descricao.isEmpty()) {
            return null;
        }

        // Fatura fecha ~1 mês antes do vencimento: lançamento com mês MAIOR que o mês de
        // vencimento pertence ao ano anterior (ex.: vencimento 10/03/2025, lançamento 28/11
        // é 28/11/2024).
        int ano = mes > mesVencimento ? anoVencimento - 1 : anoVencimento;
        LocalDate data = LocalDate.of(ano, mes, dia);
        return new TransacaoItau(data, descricao, valor, installmentNumber, installmentTotal);
```

Em `toDto`, trocar:
```java
    private NormalizedTransactionDTO toDto(TransacaoItau t) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(t.valor().abs(), BigDecimal.ONE));
        fields.put("transaction_date", new StagedFieldValueDTO(t.data().toString(), BigDecimal.ONE));
        // Sinal do valor decide direção: negativo = estorno/abatimento (credit), positivo =
        // compra normal (debit) — confiança 1.0 porque o sinal veio do padrão do template, não
        // de inferência posicional (diferente da heurística genérica, confiança 0.7).
        fields.put("direction",
                new StagedFieldValueDTO(t.valor().signum() < 0 ? "credit" : "debit", BigDecimal.ONE));
        fields.put("description", new StagedFieldValueDTO(t.descricao(), new BigDecimal("0.9")));
        BigDecimal overallConfidence = fields.get("amount").confidence().min(fields.get("transaction_date").confidence());
        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }
```
por:
```java
    private NormalizedTransactionDTO toDto(TransacaoItau t) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(t.valor().abs(), BigDecimal.ONE));
        fields.put("transaction_date", new StagedFieldValueDTO(t.data().toString(), BigDecimal.ONE));
        // Sinal do valor decide direção: negativo = estorno/abatimento (credit), positivo =
        // compra normal (debit) — confiança 1.0 porque o sinal veio do padrão do template, não
        // de inferência posicional (diferente da heurística genérica, confiança 0.7).
        fields.put("direction",
                new StagedFieldValueDTO(t.valor().signum() < 0 ? "credit" : "debit", BigDecimal.ONE));
        fields.put("description", new StagedFieldValueDTO(t.descricao(), new BigDecimal("0.9")));
        // Metadado de parcela — só presente quando a linha trazia o marcador "NN/MM". Confiança
        // 1.0: veio de um padrão regex casado, não de inferência. O ImportService.commit() usa
        // isso pra decidir se a parcela 1 vira um InstallmentGroup completo.
        if (t.installmentNumber() != null) {
            fields.put("installment_number", new StagedFieldValueDTO(t.installmentNumber(), BigDecimal.ONE));
            fields.put("installment_total", new StagedFieldValueDTO(t.installmentTotal(), BigDecimal.ONE));
        }
        BigDecimal overallConfidence = fields.get("amount").confidence().min(fields.get("transaction_date").confidence());
        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }
```

- [ ] **Step 4: Rodar os testes, confirmar que passam**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS (todos os testes do arquivo, incluindo os 2 novos)

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "$(cat <<'EOF'
feat(import): ItauFaturaTemplate preserva número/total de parcela

Antes o marcador "NN/MM" (ex. "04/06") era só removido da descrição.
Agora é capturado nos campos installment_number/installment_total do
fields JSONB antes de ser removido — pré-requisito pro commit
reconhecer parcela 1 e criar o parcelamento completo.
EOF
)"
```

---

### Task 2: `ImportService.commit()` cria parcelamento completo na parcela 1

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/ImportService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java`

**Interfaces:**
- Consumes: `fields.installment_number`/`fields.installment_total` (Task 1).
- Consumes: `TransactionRequestDTO(description, amount, date, type, status, totalInstallments, categoryId, accountId)` — já existe, `totalInstallments>1` já cria `InstallmentGroup` completo (`TransactionService.create`, inalterado por este plano).

- [ ] **Step 1: Escrever os testes**

Adicionar a `ImportServiceTest.java` um helper de conta CREDIT_CARD (import novo: `com.fintech.api.domain.account.CreditCardDetails`, `com.fintech.api.repository.CreditCardDetailsRepository`) e uma fixture com os campos de parcela:

```java
    @Autowired CreditCardDetailsRepository creditCardDetailsRepository;
```

```java
    private Account persistCreditCardAccount(Tenant tenant, User user) {
        Account account = accountRepository.save(Account.builder()
                .name("Cartão Itaú")
                .type(AccountType.CREDIT_CARD)
                .countInLiquidBalance(false)
                .countInNetWorth(true)
                .active(true)
                .tenant(tenant)
                .createdBy(user)
                .build());
        creditCardDetailsRepository.save(CreditCardDetails.builder()
                .account(account)
                .closingDay(5)
                .dueDay(15)
                .build());
        return account;
    }
```

```java
    /** Parcela 1 de 3, alta confiança — deve virar InstallmentGroup completo no commit. */
    private NormalizedTransactionDTO primeiraParcelaDeTres() {
        return new NormalizedTransactionDTO(
                null,
                Map.of("amount", fieldValue(100.00, "1.0"),
                        "transaction_date", fieldValue("2026-06-10", "1.0"),
                        "description", fieldValue("LOJA PARCELADA", "0.9"),
                        "installment_number", fieldValue(1, "1.0"),
                        "installment_total", fieldValue(3, "1.0")),
                null, null,
                new BigDecimal("0.98"),
                null, null);
    }

    /** Parcela 3 de 3 — NÃO é a primeira, deve comitar como avulsa (comportamento inalterado). */
    private NormalizedTransactionDTO parcelaNaoInicial() {
        return new NormalizedTransactionDTO(
                null,
                Map.of("amount", fieldValue(50.00, "1.0"),
                        "transaction_date", fieldValue("2026-06-10", "1.0"),
                        "description", fieldValue("OUTRA LOJA", "0.9"),
                        "installment_number", fieldValue(3, "1.0"),
                        "installment_total", fieldValue(3, "1.0")),
                null, null,
                new BigDecimal("0.98"),
                null, null);
    }
```

```java
    @Test
    void commitDaParcela1CriaInstallmentGroupCompleto() {
        Tenant tenant = persistTenant("Tenant Parcelamento");
        User user = persistUser(tenant, "parcela1@import.test");
        Account account = persistCreditCardAccount(tenant, user);

        ImportBatchResponseDTO batch = importService.createBatch(batchOf(primeiraParcelaDeTres()), user);
        UUID stagedId = importService.listStaged(batch.id(), user).get(0).id();

        ImportCommitRequestDTO req = new ImportCommitRequestDTO(
                List.of(new StagedCommitItemDTO(stagedId, account.getId(), null)));
        importService.commit(batch.id(), req, user);

        StagedTransactionResponseDTO afterStaged = importService.listStaged(batch.id(), user).get(0);
        TransactionResponseDTO primeira = transactionService.findById(afterStaged.promotedTransactionId(), user);

        // amount = 100.00 (parcela) × 3 = 300.00 dividido de volta em 3 pelo TransactionService —
        // cada parcela 100.00 exato (sem resíduo neste caso).
        assertThat(primeira.amount()).isEqualByComparingTo("100.00");
        assertThat(primeira.installmentNumber()).isEqualTo(1);
        assertThat(primeira.totalInstallments()).isEqualTo(3);

        // As outras 2 parcelas do grupo existem no sistema, mesmo sem staged própria — prova
        // que o InstallmentGroup completo foi criado, não só a transação da parcela 1.
        // Assinatura real: findAll(User, invoiceId, accountIds, status, type, startDate, endDate).
        List<TransactionResponseDTO> doGrupo = transactionService.findAll(
                user, null, List.of(account.getId()), null, null, null, null);
        long parcelasDoGrupo = doGrupo.stream()
                .filter(t -> "LOJA PARCELADA".equals(t.description()))
                .count();
        assertThat(parcelasDoGrupo).isEqualTo(3);
    }

    @Test
    void commitDeParcelaNaoInicialComitaComoAvulsa() {
        Tenant tenant = persistTenant("Tenant Parcela Avulsa");
        User user = persistUser(tenant, "parcelanaoinicial@import.test");
        Account account = persistCreditCardAccount(tenant, user);

        ImportBatchResponseDTO batch = importService.createBatch(batchOf(parcelaNaoInicial()), user);
        UUID stagedId = importService.listStaged(batch.id(), user).get(0).id();

        ImportCommitRequestDTO req = new ImportCommitRequestDTO(
                List.of(new StagedCommitItemDTO(stagedId, account.getId(), null)));
        importService.commit(batch.id(), req, user);

        StagedTransactionResponseDTO afterStaged = importService.listStaged(batch.id(), user).get(0);
        TransactionResponseDTO tx = transactionService.findById(afterStaged.promotedTransactionId(), user);

        // Avulsa: valor da parcela tal como veio (50.00), sem multiplicar, sem parcelamento.
        assertThat(tx.amount()).isEqualByComparingTo("50.00");
        assertThat(tx.totalInstallments()).isEqualTo(1);
    }
```

- [ ] **Step 2: Rodar os testes, confirmar que falham do jeito certo**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceTest#commitDaParcela1CriaInstallmentGroupCompleto+commitDeParcelaNaoInicialComitaComoAvulsa`
Expected: FAIL no primeiro (hoje cria só 1 transação avulsa, `totalInstallments` sai `1`, `parcelasDoGrupo` sai `1` não `3`). O segundo já deve passar sem mudança (comportamento que já existe) — se passar antes do Step 3, tudo bem, não é um requisito de "falhar primeiro" pra esse caso específico.

- [ ] **Step 3: Implementar**

Em `ImportService.java`, dentro do loop de `commit()`, trocar:
```java
            String description = fieldValue(staged, "description", this::toStr);
            if (description == null || description.isBlank()) {
                description = "Importado de comprovante";
            }

            // status = null → create() aplica o default (PENDING), mesma semântica de um lançamento manual.
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    description, amount, date, type, (TransactionStatus) null, null, item.categoryId(), item.accountId());
            List<TransactionResponseDTO> created = transactionService.create(dto, user);
            TransactionResponseDTO tx = created.get(0);
```
por:
```java
            String description = fieldValue(staged, "description", this::toStr);
            if (description == null || description.isBlank()) {
                description = "Importado de comprovante";
            }

            // Parcela 1 de um parcelamento reconhecido (hoje só o ItauFaturaTemplate preenche
            // esses campos): reusa o MESMO caminho de criação parcelada do lançamento manual —
            // o valor aproximado (parcela × N) é dividido de volta pela mesma regra de resíduo,
            // então a soma bate. Parcela > 1 sem grupo correspondente no sistema seria
            // reconciliação de verdade (fora de escopo — spec import-itau-parcelamento §7); cai
            // no caminho avulso de sempre.
            Integer installmentNumber = fieldValue(staged, "installment_number", this::toInteger);
            Integer installmentTotal = fieldValue(staged, "installment_total", this::toInteger);
            BigDecimal requestAmount = amount;
            Integer totalInstallments = null;
            if (installmentNumber != null && installmentNumber == 1
                    && installmentTotal != null && installmentTotal > 1) {
                requestAmount = amount.multiply(BigDecimal.valueOf(installmentTotal));
                totalInstallments = installmentTotal;
            }

            // status = null → create() aplica o default (PENDING), mesma semântica de um lançamento manual.
            TransactionRequestDTO dto = new TransactionRequestDTO(
                    description, requestAmount, date, type, (TransactionStatus) null, totalInstallments,
                    item.categoryId(), item.accountId());
            List<TransactionResponseDTO> created = transactionService.create(dto, user);
            TransactionResponseDTO tx = created.get(0);
```

Adicionar o helper `toInteger` junto dos outros coercers (`toBigDecimal`/`toLocalDate`/`toStr`):
```java
    private Integer toInteger(Object v) {
        BigDecimal b = toBigDecimal(v);
        return b == null ? null : b.intValue();
    }
```

- [ ] **Step 4: Rodar os testes, confirmar que passam**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceTest`
Expected: PASS em todos os testes do arquivo, incluindo os 2 novos e os já existentes (`commitPromoveStagedParaTransacaoEFechaOBatch`, `commitMapeiaDirectionCreditParaINCOME` — sem `installment_number`/`installment_total` nos fields, então caem no `else` implícito, comportamento idêntico a hoje).

- [ ] **Step 5: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/ImportService.java \
        src/test/java/com/fintech/api/service/imports/ImportServiceTest.java
git commit -m "$(cat <<'EOF'
feat(import): commit da parcela 1 cria InstallmentGroup completo

Reusa TransactionService.create (já suporta totalInstallments,
divide o valor com a mesma regra de resíduo do lançamento manual) —
zero código de domínio novo. Parcela >1 sem grupo correspondente no
sistema continua avulsa (dívida técnica registrada na spec — casar
contra grupo existente é reconciliação, fica pra Fase 4/5).
EOF
)"
```

---

### Task 3: Frontend — lógica pura de agrupamento avulsa/parcelada

**Files:**
- Modify: `frontend/src/app/features/import/import-utils.ts`
- Modify: `frontend/src/app/features/import/import-utils.spec.ts`

**Interfaces:**
- Produces: `installmentInfo(staged): { number: number; total: number } | null`
- Produces: `installmentBadgeText(info): string | null` (`null` = parcela 1, sem aviso — só o rótulo de seção já diz "vai virar parcelamento"; texto de aviso só pra `>1`)
- Produces: `type RowGroup<T> = { kind: 'avulsas' | 'parceladas'; label: string; rows: T[] }` e `groupRowsByInstallment<T extends { installmentNumber: number | null }>(rows: T[]): RowGroup<T>[]` — usado pela Task 4.

- [ ] **Step 1: Escrever os testes**

Adicionar a `import-utils.spec.ts` (conferir o import do `describe`/`it` já usado no arquivo antes de escrever — seguir o padrão existente):

```typescript
describe('installmentInfo', () => {
  it('lê installment_number/installment_total quando presentes', () => {
    const staged = {
      fields: {
        installment_number: { value: 4, confidence: 1 },
        installment_total: { value: 6, confidence: 1 },
      },
    } as unknown as StagedTransactionResponseDTO;

    expect(installmentInfo(staged)).toEqual({ number: 4, total: 6 });
  });

  it('devolve null quando os campos não existem', () => {
    const staged = { fields: {} } as unknown as StagedTransactionResponseDTO;
    expect(installmentInfo(staged)).toBeNull();
  });
});

describe('installmentBadgeText', () => {
  it('null pra parcela 1 (sem aviso — o rótulo de seção já basta)', () => {
    expect(installmentBadgeText({ number: 1, total: 6 })).toBeNull();
  });

  it('aviso pra parcela > 1', () => {
    expect(installmentBadgeText({ number: 4, total: 6 })).toBe(
      'Parcela 4 de 6 — parcelas anteriores não importadas, confira antes de lançar',
    );
  });
});

describe('groupRowsByInstallment', () => {
  it('separa avulsas de parceladas preservando a ordem original dentro de cada grupo', () => {
    const rows = [
      { stagedId: 'a', installmentNumber: null },
      { stagedId: 'b', installmentNumber: 1 },
      { stagedId: 'c', installmentNumber: null },
      { stagedId: 'd', installmentNumber: 3 },
    ];

    const groups = groupRowsByInstallment(rows);

    expect(groups).toHaveLength(2);
    expect(groups[0].kind).toBe('avulsas');
    expect(groups[0].rows.map((r) => r.stagedId)).toEqual(['a', 'c']);
    expect(groups[1].kind).toBe('parceladas');
    expect(groups[1].rows.map((r) => r.stagedId)).toEqual(['b', 'd']);
  });

  it('omite um grupo vazio (todas avulsas → só 1 grupo na saída)', () => {
    const rows = [{ stagedId: 'a', installmentNumber: null }];
    const groups = groupRowsByInstallment(rows);
    expect(groups).toHaveLength(1);
    expect(groups[0].kind).toBe('avulsas');
  });
});
```

- [ ] **Step 2: Rodar os testes, confirmar que falham**

Run: `cd frontend && npx vitest run src/app/features/import/import-utils.spec.ts`
Expected: FAIL — `installmentInfo`/`installmentBadgeText`/`groupRowsByInstallment` não existem ainda. (Este arquivo é lógica pura sem Angular — `npx vitest` direto é seguro aqui, diferente de specs de componente.)

- [ ] **Step 3: Implementar**

Adicionar a `import-utils.ts`, perto de `fieldValue`/`fieldOf`:

```typescript
export interface InstallmentInfo {
  number: number;
  total: number;
}

/** Lê installment_number/installment_total do fields — ausentes (Nubank/genérico) → null. */
export function installmentInfo(staged: StagedTransactionResponseDTO): InstallmentInfo | null {
  const number = fieldValue(staged, 'installment_number');
  const total = fieldValue(staged, 'installment_total');
  if (typeof number !== 'number' || typeof total !== 'number') {
    return null;
  }
  return { number, total };
}

/** Aviso só pra parcela > 1 (a 1 já é anunciada pelo rótulo da seção "vai criar parcelamento"). */
export function installmentBadgeText(info: InstallmentInfo): string | null {
  if (info.number === 1) {
    return null;
  }
  return `Parcela ${info.number} de ${info.total} — parcelas anteriores não importadas, confira antes de lançar`;
}

export interface RowGroup<T> {
  kind: 'avulsas' | 'parceladas';
  label: string;
  rows: T[];
}

/**
 * Particiona linhas em Avulsas/Parceladas preservando a ordem original dentro de cada grupo.
 * Grupo vazio não aparece na saída — a UI não precisa checar tamanho antes de renderizar.
 */
export function groupRowsByInstallment<T extends { installmentNumber: number | null }>(
  rows: T[],
): RowGroup<T>[] {
  const avulsas = rows.filter((r) => r.installmentNumber === null);
  const parceladas = rows.filter((r) => r.installmentNumber !== null);
  const groups: RowGroup<T>[] = [];
  if (avulsas.length > 0) {
    groups.push({ kind: 'avulsas', label: 'Avulsas', rows: avulsas });
  }
  if (parceladas.length > 0) {
    groups.push({ kind: 'parceladas', label: 'Parceladas', rows: parceladas });
  }
  return groups;
}
```

- [ ] **Step 4: Rodar os testes, confirmar que passam**

Run: `cd frontend && npx vitest run src/app/features/import/import-utils.spec.ts`
Expected: PASS em todos os testes do arquivo (novos + já existentes).

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/app/features/import/import-utils.ts src/app/features/import/import-utils.spec.ts
git commit -m "$(cat <<'EOF'
feat(import): lógica pura de agrupamento avulsa/parcelada na revisão

installmentInfo lê os campos novos do backend (Task 1),
installmentBadgeText monta o aviso só pra parcela >1,
groupRowsByInstallment particiona a lista preservando ordem. Tudo
testável sem TestBed — a Task 4 conecta isso ao componente.
EOF
)"
```

---

### Task 4: Frontend — componente e template mostram os grupos

**Files:**
- Modify: `frontend/src/app/features/import/import.ts`
- Modify: `frontend/src/app/features/import/import.html`
- Modify: `frontend/src/app/features/import/import.scss`

**Interfaces:**
- Consumes: `installmentInfo`, `installmentBadgeText`, `groupRowsByInstallment`, `RowGroup<T>` (Task 3).

**Contexto (padrão já usado no projeto, não repetir na leitura de código):** `transaction-list.ts`/`.html` já resolve "linha de cabeçalho de seção dentro de uma `mat-table`" com uma união discriminada (`kind`) misturada às linhas de dado no MESMO array, e `matRowDef` com predicado `when` escolhendo o template certo (linha normal vs. linha de cabeçalho full-width via `colspan`). Este task aplica o mesmo padrão aqui.

**Risco conhecido, aceito:** o agrupamento acontece ANTES da paginação (sobre `rows()` inteiro, cabeçalhos inclusos no array que `pagedRows()` fatia) — em batches grandes (>25 linhas, page size default), um grupo pode ficar com o cabeçalho numa página e as linhas na próxima. Batches típicos de fatura de cartão costumam caber em 1-2 páginas; aceitável por ora, sem solução nesta fatia.

- [ ] **Step 1: Adicionar o campo ao `ReviewRow` e popular em `toRow`**

Em `import.ts`, trocar a interface `ReviewRow` — adicionar o campo depois de `duplicateCandidateOf`:
```typescript
interface ReviewRow {
  stagedId: string;
  status: string;
  requiresReview: boolean;
  overallConfidence: number | null;
  amount: string;
  amountDisplay: string;
  transaction_date: string;
  description: string;
  direction: string;
  payment_method: string;
  accountId: string | null;
  categoryId: string | null;
  duplicateCandidateOf: string | null;
  installmentNumber: number | null;
  installmentTotal: number | null;
  confidences: Record<ReviewFieldKey, number | null>;
}
```

No import do topo do arquivo, trocar:
```typescript
  type CategoryOption,
  type DuplicateConflict,
  type ReviewFieldKey,
} from './import-utils';
```
por:
```typescript
  type CategoryOption,
  type DuplicateConflict,
  type ReviewFieldKey,
  type RowGroup,
  groupRowsByInstallment,
  installmentBadgeText,
  installmentInfo,
} from './import-utils';
```
(mantém as importações de função — `fieldValueAsString`, `fieldConfidence`, `formatAmountDisplay`, `flattenCategories`, `isReadyToCommit` etc. — já presentes no bloco de import; só adicionar as três novas.)

Em `toRow(s)`, trocar:
```typescript
      duplicateCandidateOf: s.duplicateCandidateOf ?? null,
      confidences: {
```
por:
```typescript
      duplicateCandidateOf: s.duplicateCandidateOf ?? null,
      installmentNumber: installmentInfo(s)?.number ?? null,
      installmentTotal: installmentInfo(s)?.total ?? null,
      confidences: {
```

- [ ] **Step 2: Grupos computados sobre `rows()` (antes da paginação)**

Perto da definição de `pagedRows`, adicionar:
```typescript
  /** Avulsas/Parceladas, na ordem — usado só pra exibir os rótulos de seção no template
   *  (não muda o array que alimenta a tabela; ver Step 3 pra como os dois se conectam). */
  readonly rowGroups = computed<RowGroup<ReviewRow>[]>(() => groupRowsByInstallment(this.rows()));

  /** Texto do badge de aviso (só quando > parcela 1) — null pra linha sem parcela ou parcela 1. */
  installmentWarning(row: ReviewRow): string | null {
    if (row.installmentNumber === null || row.installmentTotal === null) {
      return null;
    }
    return installmentBadgeText({ number: row.installmentNumber, total: row.installmentTotal });
  }

  /** true só pra parcela 1 — mostra "vai criar parcelamento completo" na coluna de flags. */
  isFirstInstallment(row: ReviewRow): boolean {
    return row.installmentNumber === 1;
  }
```

- [ ] **Step 3: `pagedRows()` fatia sobre a lista JÁ reordenada por grupo (avulsas primeiro, parceladas depois)**

Trocar:
```typescript
  readonly pagedRows = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.rows().slice(start, start + this.pageSize());
  });
```
por:
```typescript
  /** Lista reordenada: todas as avulsas primeiro, depois todas as parceladas — mesma ordem
   *  relativa dentro de cada grupo. `rowGroups` (Step 2) descreve os MESMOS dados pros rótulos
   *  de seção; aqui só achatamos de volta pra um array plano na ordem de exibição. */
  private readonly orderedRows = computed(() =>
    this.rowGroups().flatMap((g) => g.rows),
  );

  readonly pagedRows = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.orderedRows().slice(start, start + this.pageSize());
  });
```

- [ ] **Step 4: Template — badge de aviso na coluna `flags` + rótulo de seção acima da tabela**

Em `import.html`, dentro do `matColumnDef="flags"` (achar o bloco com `row.requiresReview`/`row.duplicateCandidateOf`), adicionar mais um `@if` — colar depois do `@if (row.duplicateCandidateOf)`:
```html
          @if (installmentWarning(row)) {
            <mat-icon color="warn" [matTooltip]="installmentWarning(row)!">
              repeat
            </mat-icon>
          }
          @if (isFirstInstallment(row)) {
            <mat-icon color="primary" [matTooltip]="'Vai criar parcelamento completo (' + row.installmentTotal + ' parcelas)'">
              repeat
            </mat-icon>
          }
```

Acima da `<table mat-table ...>` (achar a linha `mat-table` / `[dataSource]="pagedRows()"` — inserir IMEDIATAMENTE antes do elemento `<table`), adicionar um resumo simples de contagem por grupo (mais simples e sem risco de paginação partida que uma linha `matRowDef` full-width — ver "Risco conhecido" acima):
```html
    @if (rowGroups().length > 1) {
      <div class="installment-groups-summary">
        @for (group of rowGroups(); track group.kind) {
          <span class="group-chip" [class.group-chip--parceladas]="group.kind === 'parceladas'">
            {{ group.label }} ({{ group.rows.length }})
          </span>
        }
      </div>
    }
```

- [ ] **Step 5: Estilo mínimo pros chips**

Em `import.scss`, adicionar:
```scss
.installment-groups-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.group-chip {
  padding: 4px 12px;
  border-radius: 16px;
  font-size: 0.875rem;
  background: var(--mat-sys-surface-container);
  color: var(--mat-sys-on-surface);

  &--parceladas {
    background: var(--mat-sys-tertiary-container);
    color: var(--mat-sys-on-tertiary-container);
  }
}
```

- [ ] **Step 6: Rodar a suíte de testes do frontend**

Run: `cd frontend && npm test` (NÃO `npx vitest` cru — specs de componente quebram fora do builder Angular, regra do projeto)
Expected: PASS em todos os specs, incluindo `import.spec.ts`/`import-utils.spec.ts`.

- [ ] **Step 7: Verificação manual no navegador (obrigatória — mudança de UI)**

```bash
docker compose up -d   # se ainda não estiver rodando
cd backend && ./mvnw spring-boot:run &
cd frontend && npm start
```
Login com `carlos@costa.com`/`costa123`. Ir em Importação, subir um PDF de fatura Itaú com pelo menos uma linha parcelada (real ou fixture local) e uma avulsa. Conferir visualmente:
- Duas seções aparecem quando há avulsa E parcelada (chips "Avulsas (N)" / "Parceladas (M)").
- Linha de parcela 1 mostra o ícone de "vai criar parcelamento completo" com o tooltip certo.
- Linha de parcela `>1` mostra o ícone de aviso com o tooltip certo.
- Commit de uma parcela 1 gera as N transações (conferir na lista de transações da conta depois).

Se algo não bater visualmente com o esperado, ajustar antes de considerar a task concluída — testes automatizados não substituem essa checagem (regra do projeto pra mudança de frontend).

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/app/features/import/import.ts src/app/features/import/import.html src/app/features/import/import.scss
git commit -m "$(cat <<'EOF'
feat(import): revisão em lote agrupa visualmente avulsas e parceladas

Chips de contagem por grupo acima da tabela + ícone de aviso na
coluna de flags (parcela >1 sem grupo conhecido no sistema) e ícone
informativo (parcela 1, vai criar o parcelamento completo). Lista
reordenada (avulsas primeiro) antes da paginação client-side.
EOF
)"
```

---

## Nota de execução — worktree

Por `git-operator.md`: nova branch a partir da `develop` atualizada (spec e este plano já
commitados nela).

```bash
cd ~/fintech-core
git pull origin develop
git worktree add -b feature/import-itau-parcelamento ~/fintech-core/.worktrees/import-itau-parcelamento develop
cd ~/fintech-core/.worktrees/import-itau-parcelamento
```
