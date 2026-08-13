# Fatura importada ancora suas próprias linhas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o commit de uma fatura Itaú importada ancora TODA linha (avulsa, parcela 1 ou
parcela `>1/N` em andamento) na fatura que o próprio documento representa — em vez de
recalcular por `resolveInvoiceMonth(data, closingDay)`, que erra quando a data de compra é
antiga (parcela em andamento) ou o `closingDay` configurado diverge do ciclo real do cartão.

**Arquitetura:** o vencimento (já parseado hoje pelo `ItauFaturaTemplate`) vira um fato de
nível de BATCH — `targetInvoiceReferenceYear/Month`, nullable, persistido em
`import_batches`. Só `ItauFaturaTemplate` popula (via novo método default na interface
`PdfBankTemplate`); os demais extratores (CSV/OFX/imagem/heurística genérica) deixam `null` e
o comportamento existente (`resolveInvoiceMonth` por transação) fica intacto. No commit, um
novo overload de `TransactionService.create` usa esse mês como âncora da parcela `i=0`,
cascateando parcelas futuras normalmente a partir dela.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, Flyway, JUnit 5 + AssertJ, integração
leve contra Postgres dev (padrão já usado em `ImportServiceTest`, sem Testcontainers).

## Global Constraints

- Lançamento manual (`POST /api/transactions`, `TransactionRequestDTO`) permanece 100%
  inalterado — sem campo novo no contrato público, sem mudança de comportamento.
- CSV/OFX/imagem/heurística genérica de PDF/Nubank seguem sem "fatura-alvo" (campos `null`) —
  zero mudança de comportamento pra eles.
- Migrations imutáveis: `V30` é aditiva (`ALTER TABLE ... ADD COLUMN`, nullable, sem
  `NOT NULL`, sem default obrigatório). `V24`/`V27` (seeds já aplicados) NÃO são editados —
  mesmo precedente do `V28`: coluna nova fica `NULL` onde o conceito não se aplica, documentado
  em comentário na própria `V30`, sem `UPDATE` retroativo forçado.
- SemVer: **PATCH** — nenhuma mudança de contrato REST.
- Toda transação criada por `TransactionService.create` deve seguir escopada pelo tenant do
  usuário autenticado — nenhuma mudança nesta entrega toca isolamento de tenant (já garantido
  pelos métodos existentes que os overloads reusam).
- Spec completa: `docs/superpowers/specs/2026-08-09-itau-fatura-ancora-por-documento-design.md`.

---

### Task 1: Fatura-alvo entra no batch — schema, DTO, persistência

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__import_batch_target_invoice.sql`
- Modify: `backend/src/main/java/com/fintech/api/domain/imports/ImportBatch.java`
- Modify: `backend/src/main/java/com/fintech/api/dto/imports/NormalizedBatchDTO.java`
- Modify: `backend/src/main/java/com/fintech/api/service/imports/ImportService.java` (só `createBatch`)
- Test: `backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java`

**Interfaces:**
- Produces: `NormalizedBatchDTO` ganha 2 componentes novos no fim,
  `Integer targetInvoiceReferenceYear`, `Integer targetInvoiceReferenceMonth` — canônico agora
  tem 12 componentes. Os 2 construtores de compat existentes (5-arg e 10-arg) continuam
  compilando, delegando pro canônico com `null, null` no final.
- Produces: `ImportBatch` ganha `getTargetInvoiceReferenceYear()`/`getTargetInvoiceReferenceMonth()`
  (Lombok `@Data`, getters automáticos).

- [ ] **Step 1: Write the failing test**

Em `ImportServiceTest.java`, logo após `createBatchPersisteProveniênciaEstruturadaDoNormalizedBatch`
(linha ~278):

```java
/**
 * Fatura-alvo do documento (spec 2026-08-09): o mês de referência que o EXTRATOR já sabe
 * (vencimento impresso na fatura Itaú) tem que sobreviver ao round-trip até a entidade —
 * mesmo padrão da proveniência do V28, mesma razão (não exposto no DTO de resposta ainda).
 */
@Test
void createBatchPersisteFaturaAlvoDoDocumento() {
    Tenant tenant = persistTenant("Tenant Target Invoice");
    User user = persistUser(tenant, "target@import.test");

    NormalizedBatchDTO comFaturaAlvo = new NormalizedBatchDTO(
            ImportMode.NEW_TRANSACTIONS, ImportSourceType.PDF_TEXT,
            "itau_fatura_v1", "v1", List.of(highConfidence()),
            null, null, null, null, null,
            2026, 7);

    ImportBatchResponseDTO created = importService.createBatch(comFaturaAlvo, user);

    ImportBatch persisted = importBatchRepository.findById(created.id()).orElseThrow();
    assertThat(persisted.getTargetInvoiceReferenceYear()).isEqualTo(2026);
    assertThat(persisted.getTargetInvoiceReferenceMonth()).isEqualTo(7);
}

/** Sem fatura-alvo (CSV/OFX/imagem/heurística) → NULL persistido, comportamento inalterado. */
@Test
void createBatchSemFaturaAlvoPersisteNull() {
    Tenant tenant = persistTenant("Tenant No Target Invoice");
    User user = persistUser(tenant, "notarget@import.test");

    ImportBatchResponseDTO created = importService.createBatch(batchOf(highConfidence()), user);

    ImportBatch persisted = importBatchRepository.findById(created.id()).orElseThrow();
    assertThat(persisted.getTargetInvoiceReferenceYear()).isNull();
    assertThat(persisted.getTargetInvoiceReferenceMonth()).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceTest#createBatchPersisteFaturaAlvoDoDocumento`
Expected: FAIL — não compila (`NormalizedBatchDTO` não tem construtor de 12 args ainda).

- [ ] **Step 3: Migration V30**

```sql
-- V30 — fatura-alvo do documento importado (Itaú), spec 2026-08-09-itau-fatura-ancora-por-
-- documento.
--
-- Motivação: o commit de importação reusava resolveInvoiceMonth (mesmo caminho de um
-- lançamento manual) para decidir em que fatura cada linha cai — recalculando uma decisão que
-- o PRÓPRIO documento já tomou (a fatura Itaú tem um vencimento único, impresso). Isso mandava
-- parcelas em andamento (>1/N, data de compra antiga) para faturas erradas, mesmo com o
-- closingDay da conta corretamente configurado (spec §1).
--
-- NULLABLE, sem backfill: só ItauFaturaTemplate tem o conceito de "1 documento = 1 fatura com
-- vencimento único" (spec, decisão d). CSV/OFX/imagem/heurística genérica de PDF são
-- extratos/comprovantes sem esse conceito — permanecem NULL, e o commit cai no caminho
-- existente (resolveInvoiceMonth), comportamento idêntico ao de hoje.
--
-- Por que V24 (seed batch de imagem) e V27 (seed batch CSV) NÃO são tocados aqui: são
-- migrations imutáveis, e o conceito de fatura-alvo genuinamente NÃO SE APLICA a nenhum dos
-- dois — mesmo precedente do V28, que só fez backfill onde o dado era DERIVÁVEL do que já
-- existia (extractor_provider a partir de extractor_used). Aqui não há nada a derivar: NULL é
-- o valor correto, não uma lacuna.

ALTER TABLE import_batches ADD COLUMN target_invoice_reference_year INTEGER;
ALTER TABLE import_batches ADD COLUMN target_invoice_reference_month INTEGER;

COMMENT ON COLUMN import_batches.target_invoice_reference_year IS
    'Ano de referência da fatura que O DOCUMENTO IMPORTADO representa (vencimento impresso, não recalculado por closingDay) — só ItauFaturaTemplate popula. NULL = extrator sem esse conceito, commit cai no caminho existente (resolveInvoiceMonth por transação).';
COMMENT ON COLUMN import_batches.target_invoice_reference_month IS
    'Mês de referência (1-12), par de target_invoice_reference_year. Ambos NULL ou ambos preenchidos.';
```

- [ ] **Step 4: `NormalizedBatchDTO` — 2 campos novos + compat**

Substituir o arquivo inteiro por:

```java
package com.fintech.api.dto.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * "Batch normalizado" de ENTRADA do {@code createBatch}: o pacote inteiro que um extrator
 * produziu (proveniência + lista de transações no schema normalizado). Na Fase 0 é o corpo do
 * endpoint {@code POST /api/imports/mock}, que prova o pipeline ponta a ponta sem extrator real.
 *
 * <p>{@code source} do §5.2 (nested {@code {type, extractor_used, extractor_version}}) é achatado
 * aqui para bater 1:1 com as colunas de {@code import_batches}.
 *
 * <p><b>Proveniência estruturada (V28, Onda 3):</b> {@code extractorProvider}/{@code
 * extractorModel}/{@code extractionLatencyMs}/{@code fallbackFrom}/{@code fallbackReason} são os
 * campos que o EXTRATOR conhece (quem mediu a chamada) — quem PERSISTE é o {@code ImportService},
 * preservando a fronteira atual: extratores não tocam banco. Todos nullable e opcionais: CSV/OFX/
 * PDF texto não têm provider/modelo/latência (parser determinístico, não chamada a modelo), e
 * fallback só é preenchido quando o extrator efetivamente tentou outro provider antes (Onda 4).
 *
 * <p><b>Fatura-alvo do documento (V30, spec 2026-08-09):</b> {@code targetInvoiceReferenceYear}/
 * {@code Month} são o mês de referência da fatura que O DOCUMENTO IMPORTADO representa (não uma
 * inferência por transação) — só extratores cujo documento tem "1 fatura = 1 vencimento único"
 * populam (hoje, só {@code ItauFaturaTemplate}). {@code null} = extrator sem esse conceito; o
 * commit cai no caminho existente (recalcula por {@code resolveInvoiceMonth}).
 */
public record NormalizedBatchDTO(
        @NotNull ImportMode importMode,
        @NotNull ImportSourceType sourceType,
        String extractorUsed,
        String extractorVersion,
        @NotNull @Valid List<NormalizedTransactionDTO> transactions,
        String extractorProvider,
        String extractorModel,
        Integer extractionLatencyMs,
        String fallbackFrom,
        String fallbackReason,
        Integer targetInvoiceReferenceYear,
        Integer targetInvoiceReferenceMonth) {

    /**
     * Construtor de compatibilidade para os extratores/testes que ainda não informam proveniência
     * estruturada nem fatura-alvo (CSV, OFX, PDF texto genérico — sem provider/modelo/latência a
     * medir, sem conceito de vencimento único de documento). Evita reescrever todo chamador
     * existente só para acrescentar campos opcionais.
     */
    public NormalizedBatchDTO(
            ImportMode importMode,
            ImportSourceType sourceType,
            String extractorUsed,
            String extractorVersion,
            List<NormalizedTransactionDTO> transactions) {
        this(importMode, sourceType, extractorUsed, extractorVersion, transactions,
                null, null, null, null, null, null, null);
    }

    /**
     * Construtor de compatibilidade para chamadores que informam proveniência estruturada (V28)
     * mas não fatura-alvo (V30) — forma usada antes desta entrega.
     */
    public NormalizedBatchDTO(
            ImportMode importMode,
            ImportSourceType sourceType,
            String extractorUsed,
            String extractorVersion,
            List<NormalizedTransactionDTO> transactions,
            String extractorProvider,
            String extractorModel,
            Integer extractionLatencyMs,
            String fallbackFrom,
            String fallbackReason) {
        this(importMode, sourceType, extractorUsed, extractorVersion, transactions,
                extractorProvider, extractorModel, extractionLatencyMs, fallbackFrom, fallbackReason,
                null, null);
    }
}
```

- [ ] **Step 5: `ImportBatch` — 2 campos novos**

Em `ImportBatch.java`, logo após o campo `extractionLatencyMs` (antes de `createdAt`):

```java
    // Fatura que O DOCUMENTO IMPORTADO representa (vencimento impresso, não recalculado por
    // closingDay) — só ItauFaturaTemplate popula (V30, spec 2026-08-09). NULL = extrator sem
    // esse conceito (CSV/OFX/imagem/heurística genérica); commit() cai no caminho existente.
    @Column(name = "target_invoice_reference_year")
    private Integer targetInvoiceReferenceYear;

    @Column(name = "target_invoice_reference_month")
    private Integer targetInvoiceReferenceMonth;
```

- [ ] **Step 6: `ImportService.createBatch` — grava os 2 campos**

No builder de `ImportBatch` dentro de `createBatch` (depois de `.fallbackReason(batch.fallbackReason())`,
antes de `.status(ImportBatchStatus.EXTRACTED)`):

```java
                .targetInvoiceReferenceYear(batch.targetInvoiceReferenceYear())
                .targetInvoiceReferenceMonth(batch.targetInvoiceReferenceMonth())
```

- [ ] **Step 7: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceTest#createBatchPersisteFaturaAlvoDoDocumento+createBatchSemFaturaAlvoPersisteNull`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V30__import_batch_target_invoice.sql \
        backend/src/main/java/com/fintech/api/domain/imports/ImportBatch.java \
        backend/src/main/java/com/fintech/api/dto/imports/NormalizedBatchDTO.java \
        backend/src/main/java/com/fintech/api/service/imports/ImportService.java \
        backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java
git commit -m "feat(import): grava fatura-alvo do documento no batch (V30)"
```

---

### Task 2: `PdfBankTemplate` + `ItauFaturaTemplate` — expõe o vencimento como fatura-alvo

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/PdfBankTemplate.java`
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: nada de tasks anteriores.
- Produces: `PdfBankTemplate.targetInvoiceReferenceMonth(String fullText)` → `YearMonth`
  (default `null`). `ItauFaturaTemplate` implementa; `NubankExtratoTemplate` herda o default
  sem tocar (não tem esse conceito).

- [ ] **Step 1: Write the failing test**

Em `ItauFaturaTemplateTest.java`, adicionar:

```java
@Test
void targetInvoiceReferenceMonthDerivaDoVencimentoImpresso() {
    ItauFaturaTemplate template = new ItauFaturaTemplate();
    String texto = "FULANO DE TAL\nVencimento 10/08/2026\n60.872.504/0001-23\n";

    YearMonth alvo = template.targetInvoiceReferenceMonth(texto);

    // Vencimento 10/08 → referenceMonth = mês anterior (mesma relação que InvoiceService já
    // assume no caso dueDay >= closingDay — spec §2.c).
    assertThat(alvo).isEqualTo(YearMonth.of(2026, 7));
}

@Test
void targetInvoiceReferenceMonthLancaQuandoVencimentoAusente() {
    ItauFaturaTemplate template = new ItauFaturaTemplate();

    assertThatThrownBy(() -> template.targetInvoiceReferenceMonth("texto sem vencimento nenhum"))
            .isInstanceOf(ExtractionException.class);
}
```

Adicionar aos imports do topo do arquivo (nenhum dos três está presente hoje):

```java
import com.fintech.api.service.imports.ExtractionException;
import java.time.YearMonth;
```

e ao bloco de imports estáticos:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest#targetInvoiceReferenceMonthDerivaDoVencimentoImpresso`
Expected: FAIL — não compila (método não existe em `ItauFaturaTemplate`).

- [ ] **Step 3: `PdfBankTemplate` — novo método default**

Adicionar ao final da interface, antes do fechamento `}`:

```java
    /**
     * Mês de referência da FATURA QUE ESTE DOCUMENTO REPRESENTA (não de uma transação
     * individual) — só bancos cujo documento é "uma fatura com vencimento único" têm esse
     * conceito (Itaú). {@code null} (default): extrator genérico decide por transação,
     * comportamento inalterado (spec: 2026-08-09-itau-fatura-ancora-por-documento).
     */
    default java.time.YearMonth targetInvoiceReferenceMonth(String fullText) {
        return null;
    }
```

- [ ] **Step 4: `ItauFaturaTemplate` — refatora extração do vencimento + implementa o método**

Extrai a lógica hoje embutida em `parse()` (linhas 113-119) para um método privado reusável,
e adiciona `import java.time.YearMonth;` ao topo do arquivo.

Substituir (linhas 111-119):

```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int mesVencimento = Integer.parseInt(dueDateMatcher.group(2));
        int anoVencimento = Integer.parseInt(dueDateMatcher.group(3));
```

por:

```java
    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
        LocalDate vencimento = extrairVencimento(fullText);
        int mesVencimento = vencimento.getMonthValue();
        int anoVencimento = vencimento.getYear();
```

E adicionar, logo após `parse()` (antes de `detectColumnSplit`):

```java
    @Override
    public YearMonth targetInvoiceReferenceMonth(String fullText) {
        // dueDay >= closingDay é o caso normal do Itaú (confirmado nas 45 faturas medidas na
        // spec de coluna: vencimento sempre dia 10) — nesse caso InvoiceService.createNewInvoice
        // vence a fatura de referenceMonth no mês SEGUINTE. Recalcular pelo closingDay
        // configurado na conta reintroduziria a mesma fragilidade que causou o sintoma original
        // (spec 2026-08-09 §2.c) — o vencimento IMPRESSO já é o dado certo, independente de
        // como a conta está configurada no sistema.
        return YearMonth.from(extrairVencimento(fullText)).minusMonths(1);
    }

    /** Vencimento impresso no documento (único, aparece uma vez, com ano completo). */
    private LocalDate extrairVencimento(String fullText) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int dia = Integer.parseInt(dueDateMatcher.group(1));
        int mes = Integer.parseInt(dueDateMatcher.group(2));
        int ano = Integer.parseInt(dueDateMatcher.group(3));
        return LocalDate.of(ano, mes, dia);
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS — inclusive todos os testes PRÉ-EXISTENTES do arquivo (a refatoração de
`parse()` não muda o valor de `mesVencimento`/`anoVencimento`, só onde é calculado).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/templates/PdfBankTemplate.java \
        backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "feat(import): ItauFaturaTemplate expõe o vencimento como fatura-alvo do documento"
```

---

### Task 3: `PdfTextExtractor` — carrega a fatura-alvo no `NormalizedBatchDTO`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java`

**Interfaces:**
- Consumes: `PdfBankTemplate.targetInvoiceReferenceMonth` (Task 2), `NormalizedBatchDTO`
  12-arg canônico (Task 1).
- Produces: nada consumido por outra task — ponta final da extração.

- [ ] **Step 1: Write the failing test**

Em `PdfTextExtractorTest.java`, adicionar ao teste existente `extractUsaTemplateItauQuandoConteudoBateAssinatura`
(ele já usa um texto com `"Vencimento 10/03/2025"`) a asserção:

```java
        assertThat(batch.targetInvoiceReferenceYear()).isEqualTo(2025);
        assertThat(batch.targetInvoiceReferenceMonth()).isEqualTo(2); // vencimento 10/03 → referenceMonth = fevereiro
```

E um novo teste, provando que um template SEM esse conceito (o stub `matchingTemplate` do
teste `templateQueDescasoUsadoSeTiverMatch`, que não sobrescreve o método) deixa os campos `null`:

```java
@Test
void templateSemFaturaAlvoDeixaCamposNull() {
    var templateGenerico = new PdfBankTemplate() {
        @Override
        public boolean matches(String fullText) {
            return true;
        }

        @Override
        public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
            var fields = new LinkedHashMap<String, StagedFieldValueDTO>();
            fields.put("amount", new StagedFieldValueDTO(new BigDecimal("10.00"), BigDecimal.ONE));
            return List.of(new NormalizedTransactionDTO(null, fields, null, null, BigDecimal.ONE, null, null));
        }

        @Override
        public String templateId() {
            return "generico_sem_target";
        }
    };

    var extractorComTemplate = new PdfTextExtractor("v1-test", List.of(templateGenerico));
    NormalizedBatchDTO batch = extractorComTemplate.extract(input(pdfComTexto(
            "EXTRATO DE CONTA CORRENTE", "Banco XYZ")));

    assertThat(batch.targetInvoiceReferenceYear()).isNull();
    assertThat(batch.targetInvoiceReferenceMonth()).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PdfTextExtractorTest#extractUsaTemplateItauQuandoConteudoBateAssinatura`
Expected: FAIL — `batch.targetInvoiceReferenceYear()` é sempre `null` (extrator ainda não lê o template).

- [ ] **Step 3: Implementar**

Adicionar `import java.time.YearMonth;` ao topo do arquivo. Substituir o corpo do loop de
templates em `extract()` (linhas 133-139):

```java
        for (PdfBankTemplate template : templates) {
            if (template.matches(text)) {
                return new NormalizedBatchDTO(
                        input.mode(), ImportSourceType.PDF_TEXT, template.templateId(), extractorVersion,
                        template.parse(text, input.content()));
            }
        }
```

por:

```java
        for (PdfBankTemplate template : templates) {
            if (template.matches(text)) {
                YearMonth faturaAlvo = template.targetInvoiceReferenceMonth(text);
                return new NormalizedBatchDTO(
                        input.mode(), ImportSourceType.PDF_TEXT, template.templateId(), extractorVersion,
                        template.parse(text, input.content()),
                        null, null, null, null, null,
                        faturaAlvo != null ? faturaAlvo.getYear() : null,
                        faturaAlvo != null ? faturaAlvo.getMonthValue() : null);
            }
        }
```

(A chamada `template.parse(...)` continua ANTES de `targetInvoiceReferenceMonth` na ordem de
avaliação dos argumentos — mas como Java avalia argumentos da esquerda pra direita e
`faturaAlvo` já foi calculado numa variável local antes, a ordem de chamada real é:
`targetInvoiceReferenceMonth` primeiro, depois `parse`. Ambos usam o mesmo regex de vencimento;
se `parse()` falhasse por vencimento ausente, `targetInvoiceReferenceMonth` já teria lançado a
mesma exceção antes — nenhum comportamento observável muda.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PdfTextExtractorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java \
        backend/src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java
git commit -m "feat(import): PdfTextExtractor carrega a fatura-alvo do template no batch normalizado"
```

---

### Task 4: `TransactionService` — overload com âncora de fatura

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`
- Test: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

**Interfaces:**
- Consumes: nada de tasks anteriores (isolado).
- Produces: `TransactionService.create(TransactionRequestDTO dto, User user, YearMonth
  anchorInvoiceMonth)` — usado pela Task 5.

- [ ] **Step 1: Write the failing test**

`TransactionServiceTest.java` é teste Mockito puro (`@ExtendWith(MockitoExtension.class)`,
`@Mock`/`@InjectMocks`, sem Spring context) — usar os helpers já existentes `buildUser()` e
`buildCreditCardAccount(user)` (linhas 519-541), mesmo padrão dos testes de CREDIT_CARD já
presentes (`createsCreditCardTransactionWithInvoice`, `installmentsOnCreditCardHaveSameDateDifferentInvoices`).
Adicionar `import java.time.YearMonth;` ao topo do arquivo de teste. Adicionar, após
`nonCreditCardInstallmentsKeepDatePlusMonths` (linha ~517, antes dos helpers privados):

```java
    @Test
    @DisplayName("Âncora explícita de fatura ignora resolveInvoiceMonth (parcela em andamento com data antiga)")
    void anchorInvoiceMonthOverridesResolveInvoiceMonth() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        // Dia 13, closingDay=5 → dia > closingDay → SEM âncora, resolveInvoiceMonth mandaria
        // pra referenceMonth=março (mês da própria compra). Com âncora, deve ignorar isso.
        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Parcela em andamento", new BigDecimal("50.00"), LocalDate.of(2026, 3, 13),
                TransactionType.EXPENSE, null, null, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenReturn(Invoice.builder().id(UUID.randomUUID()).account(account)
                        .referenceYear(2026).referenceMonth(7)
                        .closingDate(LocalDate.of(2026, 8, 5))
                        .dueDate(LocalDate.of(2026, 8, 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user, YearMonth.of(2026, 7));

        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService, never()).getOrCreate(account, 2026, 3);
    }

    @Test
    @DisplayName("Parcelas futuras cascateiam a partir da âncora, não da data de compra")
    void installmentsWithAnchorCascadeFromAnchorMonth() {
        User user = buildUser();
        Account account = buildCreditCardAccount(user);
        CreditCardDetails details = new CreditCardDetails();
        details.setClosingDay(5);
        details.setDueDay(15);

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Notebook parcelado", new BigDecimal("3000.00"), LocalDate.of(2026, 3, 13),
                TransactionType.EXPENSE, null, 3, null, account.getId());

        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));
        when(creditCardDetailsRepository.findByAccount(account)).thenReturn(Optional.of(details));
        when(installmentGroupRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceService.getOrCreate(any(), anyInt(), anyInt()))
                .thenAnswer(i -> Invoice.builder()
                        .id(UUID.randomUUID()).account(account)
                        .referenceYear(i.getArgument(1))
                        .referenceMonth(i.getArgument(2))
                        .closingDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 5))
                        .dueDate(LocalDate.of(i.<Integer>getArgument(1), i.<Integer>getArgument(2), 15))
                        .status(InvoiceStatus.OPEN).build());
        when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.create(dto, user, YearMonth.of(2026, 7));

        verify(invoiceService).getOrCreate(account, 2026, 7);
        verify(invoiceService).getOrCreate(account, 2026, 8);
        verify(invoiceService).getOrCreate(account, 2026, 9);
    }
```

O overload de 2 args (sem âncora) já tem cobertura de regressão suficiente nos testes
PRÉ-EXISTENTES deste arquivo (`createsCreditCardTransactionWithInvoice`,
`purchaseAfterClosingGoesToNextMonth`, `installmentsOnCreditCardHaveSameDateDifferentInvoices`)
— eles continuam chamando `service.create(dto, user)`, que passa a delegar pro overload de 3
args com `null`; rodá-los de novo no Step 4 já prova que o caminho sem âncora não regrediu, sem
precisar duplicar teste novo pra isso.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=TransactionServiceTest#anchorInvoiceMonthOverridesResolveInvoiceMonth`
Expected: FAIL — não compila (overload de 3 args não existe).

- [ ] **Step 3: Implementar**

Em `TransactionService.java`, substituir a assinatura e o corpo do método `create` (linhas
130-198):

```java
    @Transactional
    public List<TransactionResponseDTO> create(TransactionRequestDTO dto, User user) {
        return create(dto, user, null);
    }

    /**
     * Overload usado SÓ pelo commit de importação ({@code ImportService}): quando a fatura de
     * origem já é conhecida (documento com vencimento único impresso, ex. fatura Itaú), a
     * parcela-âncora (i=0) ignora {@code resolveInvoiceMonth} e usa {@code anchorInvoiceMonth}
     * diretamente — o documento já decidiu em que fatura a linha caiu; recalcular pela data de
     * compra reintroduz a mesma fragilidade que causou o roteamento errado de parcelas em
     * andamento (spec 2026-08-09-itau-fatura-ancora-por-documento). Parcelas futuras de um
     * parcelamento novo (i=1..N-1) seguem cascata normal a partir da âncora.
     */
    @Transactional
    public List<TransactionResponseDTO> create(
            TransactionRequestDTO dto, User user, YearMonth anchorInvoiceMonth) {
        Category category = resolveCategory(dto.categoryId(), user);
        Account account = resolveAccount(dto.accountId(), user);

        int installments = (dto.totalInstallments() != null && dto.totalInstallments() > 1)
                ? dto.totalInstallments() : 1;
        // #136: dividir com DOWN e deixar a ÚLTIMA parcela absorver o resíduo garante
        // soma(parcelas) == total exatamente. HALF_EVEN uniforme perdia/ganhava centavos
        // (100/3 → 33,33×3 = 99,99). Invariante contábil: as parcelas derivam do total.
        BigDecimal installmentAmount = dto.amount()
                .divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
        BigDecimal lastInstallmentAmount = dto.amount()
                .subtract(installmentAmount.multiply(BigDecimal.valueOf(installments - 1L)));

        boolean isCreditCard = AccountType.CREDIT_CARD.equals(account.getType());
        int closingDay = 0;
        if (isCreditCard) {
            closingDay = creditCardDetailsRepository.findByAccount(account)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Detalhes do cartão não encontrados para a conta."))
                    .getClosingDay();
        }
        final int finalClosingDay = closingDay;

        InstallmentGroup group = null;
        if (installments > 1) {
            group = installmentGroupRepository.save(InstallmentGroup.builder()
                    .description(dto.description())
                    .totalAmount(dto.amount())
                    .totalInstallments(installments)
                    .account(account)
                    .category(category)
                    .tenant(user.getTenant())
                    .build());
        }

        final InstallmentGroup finalGroup = group;
        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < installments; i++) {
            Invoice invoice = null;
            LocalDate transactionDate;

            if (isCreditCard) {
                YearMonth invoiceMonth = (anchorInvoiceMonth != null
                        ? anchorInvoiceMonth
                        : resolveInvoiceMonth(dto.date(), finalClosingDay)).plusMonths(i);
                invoice = invoiceService.getOrCreate(account, invoiceMonth.getYear(), invoiceMonth.getMonthValue());
                transactionDate = dto.date(); // data de compra igual em todas as parcelas
            } else {
                transactionDate = dto.date().plusMonths(i);
            }

            created.add(repository.save(Transaction.builder()
                    .description(dto.description())
                    .amount(i == installments - 1 ? lastInstallmentAmount : installmentAmount)
                    .date(transactionDate)
                    .type(dto.type())
                    .status(dto.status() != null ? dto.status() : TransactionStatus.PENDING)
                    .installmentNumber(i + 1)
                    .totalInstallments(installments)
                    .installmentGroup(finalGroup)
                    .invoice(invoice)
                    .tenant(user.getTenant())
                    .user(user)
                    .category(category)
                    .account(account)
                    .build()));
        }
        return created.stream().map(TransactionResponseDTO::fromEntity).toList();
    }
```

(Única mudança de lógica real: a linha `YearMonth invoiceMonth = ...` — o resto é idêntico ao
método atual, só reindentado dentro do novo overload de 3 args.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=TransactionServiceTest`
Expected: PASS — inclusive TODOS os testes pré-existentes desse arquivo (o overload de 2 args
delega pro de 3 args com `null`, comportamento idêntico ao método atual).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/TransactionService.java \
        backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java
git commit -m "feat(transaction): TransactionService.create aceita âncora explícita de fatura"
```

---

### Task 5: `ImportService.commit` — usa a fatura-alvo do batch

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/ImportService.java` (só `commit`)
- Test: `backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java`

**Interfaces:**
- Consumes: `ImportBatch.getTargetInvoiceReferenceYear/Month()` (Task 1),
  `TransactionService.create(dto, user, YearMonth)` (Task 4).
- Produces: nada — task final, fecha o fluxo ponta a ponta.

Este é o teste que PROVA o fix do sintoma original (R$659,21 fora da fatura de agosto).

- [ ] **Step 1: Write the failing test**

Em `ImportServiceTest.java`, seguir o MESMO padrão de `commitDaParcela1CriaInstallmentGroupCompleto`
(propagation `NOT_SUPPORTED` + `cleanupTenant` no `finally`, porque `InvoiceService.createNewInvoice`
roda em transação própria):

```java
/** Parcela EM ANDAMENTO (não é a 1ª), com data de compra ANTIGA — o caso que motivou a spec. */
private NormalizedTransactionDTO parcelaEmAndamentoComDataAntiga() {
    return new NormalizedTransactionDTO(
            null,
            Map.of("amount", fieldValue(74.87, "1.0"),
                    "transaction_date", fieldValue("2026-07-03", "1.0"), // dia 3 <= closingDay 5 → sem âncora iria pra fatura de junho
                    "description", fieldValue("PAYPAL PARCELA ANTIGA", "0.9"),
                    "installment_number", fieldValue(2, "1.0"),
                    "installment_total", fieldValue(8, "1.0")),
            null, null,
            new BigDecimal("0.98"),
            null, null);
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void commitUsaFaturaAlvoDoDocumentoParaParcelaEmAndamento() {
    Tenant tenant = persistTenant("Tenant Fatura Alvo Commit");
    try {
        User user = persistUser(tenant, "faturaalvo@import.test");
        Account account = persistCreditCardAccount(tenant, user); // closingDay=5, dueDay=15

        // Batch com fatura-alvo = agosto/2026 (é isso que o ItauFaturaTemplate teria calculado
        // pro vencimento impresso do documento) — mesmo que a data de compra (03/07) fosse cair
        // em junho/2026 por resolveInvoiceMonth se não houvesse âncora.
        NormalizedBatchDTO batch = new NormalizedBatchDTO(
                ImportMode.NEW_TRANSACTIONS, ImportSourceType.PDF_TEXT,
                "itau_fatura_v1", "v1", List.of(parcelaEmAndamentoComDataAntiga()),
                null, null, null, null, null,
                2026, 8);

        ImportBatchResponseDTO created = importService.createBatch(batch, user);
        UUID stagedId = importService.listStaged(created.id(), user).get(0).id();

        ImportCommitRequestDTO req = new ImportCommitRequestDTO(
                List.of(new StagedCommitItemDTO(stagedId, account.getId(), null)));
        importService.commit(created.id(), req, user);

        StagedTransactionResponseDTO afterStaged = importService.listStaged(created.id(), user).get(0);
        TransactionResponseDTO tx = transactionService.findById(afterStaged.promotedTransactionId(), user);

        // Sem a âncora, resolveInvoiceMonth(03/07, closingDay=5) mandaria pra fatura de junho
        // (dueDate 15/07). COM a âncora (referenceMonth=agosto), a fatura é a de setembro.
        assertThat(tx.invoiceDueDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    } finally {
        cleanupTenant(tenant.getId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceTest#commitUsaFaturaAlvoDoDocumentoParaParcelaEmAndamento`
Expected: FAIL — `tx.invoiceDueDate()` é `2026-07-15` (comportamento atual, via `resolveInvoiceMonth`),
não `2026-09-15`.

- [ ] **Step 3: Implementar**

Adicionar `import java.time.YearMonth;` ao topo de `ImportService.java`. Em `commit()`, logo
após `ImportBatch batch = findBatch(batchId, user);`:

```java
        YearMonth targetInvoiceMonth =
                (batch.getTargetInvoiceReferenceYear() != null && batch.getTargetInvoiceReferenceMonth() != null)
                        ? YearMonth.of(batch.getTargetInvoiceReferenceYear(), batch.getTargetInvoiceReferenceMonth())
                        : null;
```

E trocar a chamada existente (dentro do loop `for (StagedCommitItemDTO item : request.items())`):

```java
            List<TransactionResponseDTO> created = transactionService.create(dto, user);
```

por:

```java
            List<TransactionResponseDTO> created = transactionService.create(dto, user, targetInvoiceMonth);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ImportServiceTest`
Expected: PASS — inclusive `commitDaParcela1CriaInstallmentGroupCompleto` e
`commitDeParcelaNaoInicialComitaComoAvulsa` (batches sem fatura-alvo, `targetInvoiceMonth=null`,
comportamento idêntico ao atual).

- [ ] **Step 5: Full backend suite**

Run: `cd backend && ./mvnw test` (background — suíte completa passa de 7 min, ver gotcha do
CLAUDE.md). Confirma que nenhum teste em outro arquivo dependia implicitamente do
comportamento antigo de `TransactionService.create`/`ImportService.commit`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/ImportService.java \
        backend/src/test/java/com/fintech/api/service/imports/ImportServiceTest.java
git commit -m "fix(import): commit ancora TODA linha do documento na fatura-alvo, não recalcula por closingDay"
```

---

## Verificação final (fora do plano de tasks — manual, pós-merge)

Reimportar a fatura de referência real na conta `Teste Importacao` (dev, mesmo processo já
usado nesta investigação: limpar a conta via pod `psql` efêmero, reimportar, conferir). Critério
de sucesso: os 123 lançamentos (R$15.739,87) caem inteiros numa única fatura (ago/2026), sem
nenhuma parcela em andamento sobrando em fatura antiga. Limpar a conta de teste depois.
