# Registry de Templates PDF (Itaú fatura + Nubank extrato) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconhecer transações reais de fatura Itaú e extrato PDF Nubank, que hoje zeram na heurística genérica do `PdfTextExtractor`, via um registry de templates por banco.

**Architecture:** Nova interface `PdfBankTemplate` (mesmo padrão de lista-de-beans-ordenada já usado para `VisionModelClient`). `PdfTextExtractor` recebe `List<PdfBankTemplate>`, tenta cada um por assinatura de conteúdo (`matches`) antes da heurística genérica; nenhum bate → comportamento atual, inalterado. Dois templates concretos: `ItauFaturaTemplate` (delimitação de seção + regex por linha, ano inferido da data de vencimento) e `NubankExtratoTemplate` (state machine linha a linha, direção pela seção "Total de entradas"/"Total de saídas" corrente).

**Tech Stack:** Java 21, Spring Boot 4 (injeção de lista de beans por `@Order`), Apache PDFBox (já dependência do projeto desde a fatia 1), JUnit 5 + AssertJ.

## Global Constraints

- Spec de origem: `docs/superpowers/specs/2026-08-05-extracao-fase3-registry-templates-design.md` — toda decisão arquitetural (§2) já está tomada; não reabrir.
- Nenhuma migration nova, nenhuma mudança de contrato (`api-spec/openapi.yaml` inalterado) — impacto SemVer **PATCH**.
- Zero regressão: PDFs que hoje caem na heurística genérica continuam caindo nela quando nenhum template bate.
- Fixtures de teste usam texto **sintético** (nomes/valores fictícios) — nunca extrato/fatura real de usuário, nem em fixture nem commitado em nenhum arquivo.
- PDFs reais usados para validar os templates ficam **fora do controle de versão**, só na máquina local de quem executa o plano.
- Commits em português, imperativo, sem `Co-Authored-By` (`git-operator.md`).

---

### Task 1: `PdfBankTemplate` — interface, wiring no `PdfTextExtractor`, extração em ordem visual

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/imports/templates/PdfBankTemplate.java`
- Modify: `backend/src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java`
- Modify: `backend/src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java`

**Interfaces:**
- Produces: `PdfBankTemplate` — `boolean matches(String fullText)`, `List<NormalizedTransactionDTO> parse(String fullText)`, `String templateId()`. Tasks 2 e 3 implementam esta interface.
- Produces: `PdfTextExtractor(String extractorVersion, List<PdfBankTemplate> templates)` — novo construtor de 2 argumentos (o de 1 argumento é removido; único call site de produção é o Spring, que resolve `List<PdfBankTemplate>` vazia se nenhum bean existir ainda).

- [ ] **Step 1: Criar a interface `PdfBankTemplate`**

```java
package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;

import java.util.List;

/**
 * Template de reconhecimento de transações por banco, dentro do {@code PdfTextExtractor}
 * (spec: registry de templates, Fase 3). Roda ANTES da heurística genérica de linha —
 * {@code matches()} decide por assinatura de conteúdo (nunca nome de arquivo), o primeiro
 * que aceitar processa via {@code parse()}. Nenhum bate → heurística genérica atual,
 * comportamento inalterado.
 */
public interface PdfBankTemplate {

    /** Assinatura de conteúdo (ex.: CNPJ da instituição + rótulo de seção conhecido). */
    boolean matches(String fullText);

    /** Reconhece as transações do documento. Só chamado quando {@link #matches} devolveu {@code true}. */
    List<NormalizedTransactionDTO> parse(String fullText);

    /** Identificador gravado em {@code extractor_used} quando este template processa. */
    String templateId();
}
```

- [ ] **Step 2: Trocar a extração de texto para ordem visual (`setSortByPosition`)**

Abrir `backend/src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java`. O
método `extractText` (linhas 138-147) usa `new PDFTextStripper()` sem configurar ordem —
isso preserva a ordem de escrita no fluxo de conteúdo do PDF, que pode não bater com a
ordem visual em layouts multi-coluna (fatura de cartão, boleto). Os templates das próximas
tasks dependem de cabeçalho de seção aparecer *antes* das linhas que ele rotula, na ordem
visual de leitura — sem isso, a delimitação de seção do Itaú (Task 2) não funciona.

Trocar:
```java
    private String extractText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
```
por:
```java
    private String extractText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
```

- [ ] **Step 3: Rodar a suíte existente do `PdfTextExtractor` — confirmar zero regressão**

Run: `cd backend && ./mvnw test -Dtest=PdfTextExtractorTest`
Expected: PASS (todos os testes da fatia 1 continuam verdes — os textos sintéticos são de
uma linha cada, `sortByPosition` não muda a ordem de conteúdo de uma única linha).

- [ ] **Step 4: Trocar o construtor para receber `List<PdfBankTemplate>` e tentar cada template antes da heurística**

No mesmo arquivo, adicionar o import `com.fintech.api.service.imports.templates.PdfBankTemplate`.

Trocar o campo/construtor (linhas 83-87):
```java
    private final String extractorVersion;

    public PdfTextExtractor(@Value("${import.pdf-text.extractor-version:v1}") String extractorVersion) {
        this.extractorVersion = extractorVersion;
    }
```
por:
```java
    private final String extractorVersion;
    private final List<PdfBankTemplate> templates;

    public PdfTextExtractor(
            @Value("${import.pdf-text.extractor-version:v1}") String extractorVersion,
            List<PdfBankTemplate> templates) {
        this.extractorVersion = extractorVersion;
        this.templates = templates;
    }
```

Trocar o corpo de `extract()` (linhas 117-136) — hoje:
```java
    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        String text = extractText(input.content());

        long meaningfulChars = text.chars().filter(c -> !Character.isWhitespace(c)).count();
        if (meaningfulChars < MIN_TEXT_CHARS) {
            throw new ExtractionException(
                    "Este PDF parece ser uma imagem digitalizada (sem texto extraível). Suporte a "
                            + "PDF escaneado ainda não está disponível — use o formulário manual ou "
                            + "envie como imagem.");
        }

        // Linhas sem os dois padrões (data + valor) simplesmente não viram transação — não é erro
        // de linha, é ausência de sinal (cabeçalho, rodapé, linha de saldo). Se NENHUMA linha do
        // documento gerar transação, o batch fica vazio e é o guard-rail já existente no
        // ImportService ("zero transações aproveitáveis" → FAILED) que lida com isso, sem
        // duplicar essa checagem aqui.
        List<NormalizedTransactionDTO> transactions = parseLines(text);

        return new NormalizedBatchDTO(input.mode(), ImportSourceType.PDF_TEXT, EXTRACTOR_USED, extractorVersion, transactions);
    }
```
por:
```java
    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        String text = extractText(input.content());

        long meaningfulChars = text.chars().filter(c -> !Character.isWhitespace(c)).count();
        if (meaningfulChars < MIN_TEXT_CHARS) {
            throw new ExtractionException(
                    "Este PDF parece ser uma imagem digitalizada (sem texto extraível). Suporte a "
                            + "PDF escaneado ainda não está disponível — use o formulário manual ou "
                            + "envie como imagem.");
        }

        for (PdfBankTemplate template : templates) {
            if (template.matches(text)) {
                return new NormalizedBatchDTO(
                        input.mode(), ImportSourceType.PDF_TEXT, template.templateId(), extractorVersion,
                        template.parse(text));
            }
        }

        // Nenhum template bateu — heurística genérica de linha (fatia 1, comportamento
        // inalterado). Linha sem data+valor não vira transação (ausência de sinal, não erro);
        // batch vazio cai no guard-rail já existente do ImportService.
        List<NormalizedTransactionDTO> transactions = parseLines(text);

        return new NormalizedBatchDTO(input.mode(), ImportSourceType.PDF_TEXT, EXTRACTOR_USED, extractorVersion, transactions);
    }
```

- [ ] **Step 5: Ajustar o teste existente para o novo construtor de 2 argumentos**

Em `PdfTextExtractorTest.java`, trocar:
```java
    private final PdfTextExtractor extractor = new PdfTextExtractor("v1-test");
```
por:
```java
    private final PdfTextExtractor extractor = new PdfTextExtractor("v1-test", List.of());
```
Adicionar `import java.util.List;` se ainda não presente no arquivo (já é usado por outros
imports de `java.util.List` no arquivo — conferir antes de duplicar).

- [ ] **Step 6: Rodar a suíte novamente — confirmar zero regressão com o novo construtor**

Run: `cd backend && ./mvnw test -Dtest=PdfTextExtractorTest`
Expected: PASS (lista vazia de templates = nenhum template tentado = mesmo caminho de
código da heurística genérica de antes).

- [ ] **Step 7: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/PdfBankTemplate.java \
        src/main/java/com/fintech/api/service/imports/PdfTextExtractor.java \
        src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java
git commit -m "$(cat <<'EOF'
feat(import): adiciona interface PdfBankTemplate e roteamento de templates no PdfTextExtractor

Extração passa a tentar templates por banco (assinatura de conteúdo)
antes da heurística genérica de linha; nenhum bate mantém o
comportamento atual. Extração de texto passa a usar ordem visual
(setSortByPosition), pré-requisito para os templates das próximas
tasks reconhecerem seção corretamente.
EOF
)"
```

---

### Task 2: `ItauFaturaTemplate`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: `PdfBankTemplate` (Task 1), `NormalizedTransactionDTO`/`StagedFieldValueDTO` (existentes).
- Produces: `ItauFaturaTemplate implements PdfBankTemplate`, `templateId() == "itau_fatura_v1"`. Bean Spring (`@Component`) — Task 4 injeta via `List<PdfBankTemplate>`.

**Contexto do parsing (não repetir na leitura de código — só aqui):** a fatura Itaú tem
datas de lançamento sem ano (`DD/MM`) — o ano é inferido da data de vencimento (aparece uma
vez, formato completo, junto ao rótulo `"Vencimento"`). Os lançamentos do ciclo ficam entre
o cabeçalho `"Lançamentos: compras e saques"` e o próximo cabeçalho de seção conhecido
(parcelas futuras, limites, encargos, internacionais, produtos e serviços) — sem essa
delimitação, "Compras parceladas - próximas faturas" (preview de parcelas de meses
seguintes, mesmo formato de linha) duplicaria como transação deste mês.

- [ ] **Step 1: Escrever o teste de `matches()`**

```java
package com.fintech.api.service.imports.templates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItauFaturaTemplateTest {

    private final ItauFaturaTemplate template = new ItauFaturaTemplate();

    @Test
    void matchesReconheceCnpjItauEHeaderDeLancamentos() {
        String texto = "algum texto\n60.872.504/0001-23\nLançamentos: compras e saques\nfim";
        assertThat(template.matches(texto)).isTrue();
    }

    @Test
    void matchesRejeitaTextoSemCnpjOuSemHeader() {
        assertThat(template.matches("Lançamentos: compras e saques sem cnpj nenhum")).isFalse();
        assertThat(template.matches("60.872.504/0001-23 sem o header de lancamentos")).isFalse();
    }

    @Test
    void templateIdEItauFaturaV1() {
        assertThat(template.templateId()).isEqualTo("itau_fatura_v1");
    }
}
```

- [ ] **Step 2: Rodar o teste, confirmar falha (classe não existe ainda)**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: FAIL — `cannot find symbol: class ItauFaturaTemplate`

- [ ] **Step 3: Implementar `matches()` e `templateId()` — mínimo para passar**

```java
package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.ExtractionException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Itaú fatura de cartão — reconhece transações da seção "Lançamentos: compras e
 * saques" (spec: registry de templates, decisões c/e). Datas de lançamento vêm sem ano
 * (DD/MM); o ano é inferido da data de vencimento, que aparece uma vez no documento com ano
 * completo.
 */
@Component
@Order(10)
public class ItauFaturaTemplate implements PdfBankTemplate {

    private static final String CNPJ_ITAU = "60.872.504/0001-23";
    private static final String HEADER_LANCAMENTOS = "Lançamentos: compras e saques";

    // Cabeçalhos de seção que fecham o bloco de lançamentos do ciclo corrente — em especial
    // "Compras parceladas - próximas faturas", que repete o MESMO formato de linha (data +
    // estabelecimento + valor) para parcelas de meses seguintes da mesma compra.
    private static final List<String> STOP_MARKERS = List.of(
            "Compras parceladas - próximas faturas",
            "Limites de crédito",
            "Encargos cobrados",
            "Lançamentos internacionais",
            "Lançamentos: produtos e serviços");

    private static final Pattern DUE_DATE =
            Pattern.compile("Vencimento\\D{0,20}(\\d{2})/(\\d{2})/(\\d{4})");
    private static final Pattern LINE_START_DATE = Pattern.compile("^(\\d{2})/(\\d{2})\\s+(.*)$");
    private static final Pattern TRAILING_AMOUNT =
            Pattern.compile("(-?)\\s*(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*$");
    private static final Pattern TRAILING_INSTALLMENT_MARKER = Pattern.compile("\\d{2}/\\d{2}\\s*$");

    @Override
    public boolean matches(String fullText) {
        return fullText.contains(CNPJ_ITAU) && fullText.contains(HEADER_LANCAMENTOS);
    }

    @Override
    public String templateId() {
        return "itau_fatura_v1";
    }

    @Override
    public List<NormalizedTransactionDTO> parse(String fullText) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int mesVencimento = Integer.parseInt(dueDateMatcher.group(2));
        int anoVencimento = Integer.parseInt(dueDateMatcher.group(3));

        int start = fullText.indexOf(HEADER_LANCAMENTOS) + HEADER_LANCAMENTOS.length();
        int stop = fullText.length();
        for (String marker : STOP_MARKERS) {
            int idx = fullText.indexOf(marker, start);
            if (idx >= 0 && idx < stop) {
                stop = idx;
            }
        }
        String secao = fullText.substring(start, stop);

        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        for (String linha : secao.lines().toList()) {
            TransacaoItau transacao = parseLinha(linha.trim(), mesVencimento, anoVencimento);
            if (transacao != null) {
                transacoes.add(toDto(transacao));
            }
        }
        return transacoes;
    }

    /** {@code null} quando a linha não bate o formato "DD/MM estabelecimento [NN/NN] valor". */
    private TransacaoItau parseLinha(String linha, int mesVencimento, int anoVencimento) {
        Matcher dateMatcher = LINE_START_DATE.matcher(linha);
        if (!dateMatcher.matches()) {
            return null;
        }
        int dia = Integer.parseInt(dateMatcher.group(1));
        int mes = Integer.parseInt(dateMatcher.group(2));
        String resto = dateMatcher.group(3);

        Matcher amountMatcher = TRAILING_AMOUNT.matcher(resto);
        if (!amountMatcher.find()) {
            return null;
        }
        BigDecimal valor = parseValorBr(amountMatcher.group(2));
        if ("-".equals(amountMatcher.group(1))) {
            valor = valor.negate();
        }

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
    }

    private BigDecimal parseValorBr(String raw) {
        return new BigDecimal(raw.replace(".", "").replace(',', '.'));
    }

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

    private record TransacaoItau(LocalDate data, String descricao, BigDecimal valor) {}
}
```

- [ ] **Step 4: Rodar o teste de `matches()`/`templateId()` — confirmar passa**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS (os 3 testes do Step 1)

- [ ] **Step 5: Escrever os testes de `parse()` — caso simples, virada de ano, exclusão de parcelas futuras, estorno**

Adicionar à mesma classe de teste:

```java
    private static final String CABECALHO_VENCIMENTO =
            "FULANO DE TAL\nVencimento 10/03/2025\n"
            + "60.872.504/0001-23\n";

    @Test
    void parseReconheceTransacaoSimplesDentroDaSecaoDeLancamentos() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2025-02-03");
        assertThat(tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("49.00"));
        assertThat(tx.fields().get("description").value()).isEqualTo("SUBWAY FAZENDINHA");
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void parseRemoveMarcadorDeParcelaColadoAoEstabelecimento() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields().get("description").value()).isEqualTo("Foco Aluguel de Ca");
        // Mês do lançamento (11) > mês de vencimento (03) → ano anterior ao de vencimento.
        assertThat(transacoes.get(0).fields().get("transaction_date").value()).isEqualTo("2024-11-28");
    }

    @Test
    void parseIgnoraComprasParceladasDeProximasFaturas() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "28/11 Foco Aluguel de Ca04/06 112,67\n"
                + "Compras parceladas - próximas faturas\n"
                + "28/11 Foco Aluguel de Ca05/06 112,67\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        // Só a parcela do ciclo corrente (04/06) — a próxima parcela (05/06), que aparece na
        // seção de preview de faturas futuras, não vira transação deste batch.
        assertThat(transacoes).hasSize(1);
    }

    @Test
    void parseTrataValorNegativoComoCreditEEstornoDeAnuidade() {
        String texto = CABECALHO_VENCIMENTO
                + "Lançamentos: compras e saques\n"
                + "03/03 ESTORNO DE ANUIDADE DIF - 29,50\n"
                + "Limites de crédito\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("29.50"));
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
        assertThat(tx.fields().get("description").value()).isEqualTo("ESTORNO DE ANUIDADE DIF");
    }
```

- [ ] **Step 6: Rodar os testes de `parse()`, confirmar passam**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS (7 testes no total)

- [ ] **Step 7: Validação manual contra a fatura real (não commitada)**

Este passo não é opcional — a ordem exata do texto extraído por `PDFTextStripper` em
layout multi-coluna real só se confirma contra o arquivo de verdade, fora do que fixture
sintética consegue provar.

Escrever um `main()` ou teste temporário local (não commitar) que carregue os bytes da
fatura Itaú real do disco, rode `ItauFaturaTemplate.matches()`/`parse()` sobre o texto
extraído (`new PdfTextExtractor(...).extractText(...)` — se `extractText` não for acessível
fora do pacote, testar via `PdfTextExtractor.extract()` completo) e imprima as transações
reconhecidas. Conferir manualmente:
- Toda linha de "Lançamentos: compras e saques" do PDF real aparece na lista.
- Nenhuma linha de "Compras parceladas - próximas faturas" aparece.
- Datas/anos batem com o esperado (inclusive lançamentos de mês diferente do vencimento).

Se a ordem real do texto divergir do assumido (por exemplo, o cabeçalho de seção não ficar
adjacente às linhas mesmo com `setSortByPosition(true)`), ajustar `STOP_MARKERS`/lógica de
delimitação nesta task antes de prosseguir — não adiar para depois do commit.

- [ ] **Step 8: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "$(cat <<'EOF'
feat(import): adiciona ItauFaturaTemplate ao registry de templates PDF

Reconhece transações da seção "Lançamentos: compras e saques" da
fatura Itaú, com ano inferido da data de vencimento e exclusão
explícita de "Compras parceladas - próximas faturas" (mesmo formato
de linha, mas pertence a faturas futuras).
EOF
)"
```

---

### Task 3: `NubankExtratoTemplate`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/service/imports/templates/NubankExtratoTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/NubankExtratoTemplateTest.java`

**Interfaces:**
- Consumes: `PdfBankTemplate` (Task 1).
- Produces: `NubankExtratoTemplate implements PdfBankTemplate`, `templateId() == "nubank_extrato_v1"`.

**Contexto do parsing:** o extrato Nubank não tem data e valor na mesma linha de forma
uniforme — datas ficam em linhas próprias (`"DD MES_PT YYYY"`), rótulos simples têm valor
na mesma linha (`"Resgate RDB 4.708,35"`), e rótulos com contraparte longa quebram em várias
linhas, com o valor sozinho na última. Direção não tem palavra-chave própria (`"Resgate
RDB"` aparece tanto em entradas quanto saídas) — o sinal confiável é a seção corrente
(`"Total de entradas"`/`"Total de saídas"`).

- [ ] **Step 1: Escrever o teste de `matches()`**

```java
package com.fintech.api.service.imports.templates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NubankExtratoTemplateTest {

    private final NubankExtratoTemplate template = new NubankExtratoTemplate();

    @Test
    void matchesReconheceCnpjNubankEHeaderDeMovimentacoes() {
        String texto = "algum texto\n18.236.120/0001-58\nMovimentações\nfim";
        assertThat(template.matches(texto)).isTrue();
    }

    @Test
    void matchesRejeitaTextoSemCnpjOuSemHeader() {
        assertThat(template.matches("Movimentações sem cnpj nenhum")).isFalse();
        assertThat(template.matches("18.236.120/0001-58 sem o header certo")).isFalse();
    }

    @Test
    void templateIdENubankExtratoV1() {
        assertThat(template.templateId()).isEqualTo("nubank_extrato_v1");
    }
}
```

- [ ] **Step 2: Rodar o teste, confirmar falha**

Run: `cd backend && ./mvnw test -Dtest=NubankExtratoTemplateTest`
Expected: FAIL — `cannot find symbol: class NubankExtratoTemplate`

- [ ] **Step 3: Implementar**

```java
package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Nubank extrato PDF — reconhece transações da seção "Movimentações" (spec:
 * registry de templates, decisão f). Datas ficam em linha própria; valor pode estar na
 * mesma linha do rótulo (entrada simples) ou sozinho na última linha de um bloco
 * multilinha (rótulo + contraparte longa). Direção vem da seção corrente ("Total de
 * entradas"/"Total de saídas"), não de palavra-chave por linha — rótulos como "Resgate RDB"
 * se repetem nos dois lados.
 */
@Component
@Order(20)
public class NubankExtratoTemplate implements PdfBankTemplate {

    private static final String CNPJ_NUBANK = "18.236.120/0001-58";
    private static final String HEADER_MOVIMENTACOES = "Movimentações";

    private static final Pattern DATE_HEADER = Pattern.compile(
            "^(\\d{2})\\s+(JAN|FEV|MAR|ABR|MAI|JUN|JUL|AGO|SET|OUT|NOV|DEZ)\\s+(\\d{4})\\b\\s*(.*)$");
    private static final Pattern TRAILING_AMOUNT =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*$");

    private static final Map<String, Integer> MESES = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEV", 2), Map.entry("MAR", 3), Map.entry("ABR", 4),
            Map.entry("MAI", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AGO", 8),
            Map.entry("SET", 9), Map.entry("OUT", 10), Map.entry("NOV", 11), Map.entry("DEZ", 12));

    @Override
    public boolean matches(String fullText) {
        return fullText.contains(CNPJ_NUBANK) && fullText.contains(HEADER_MOVIMENTACOES);
    }

    @Override
    public String templateId() {
        return "nubank_extrato_v1";
    }

    @Override
    public List<NormalizedTransactionDTO> parse(String fullText) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        LocalDate dataCorrente = null;
        String direcaoCorrente = null;
        StringBuilder acumulador = new StringBuilder();

        for (String linhaBruta : fullText.lines().toList()) {
            String linha = linhaBruta.trim();
            if (linha.isEmpty()) {
                continue;
            }

            Matcher dateMatcher = DATE_HEADER.matcher(linha);
            if (dateMatcher.matches()) {
                dataCorrente = LocalDate.of(
                        Integer.parseInt(dateMatcher.group(3)),
                        MESES.get(dateMatcher.group(2)),
                        Integer.parseInt(dateMatcher.group(1)));
                acumulador.setLength(0);
                linha = dateMatcher.group(4).trim();
                if (linha.isEmpty()) {
                    continue;
                }
            }

            if (linha.startsWith("Total de entradas")) {
                direcaoCorrente = "credit";
                acumulador.setLength(0);
                continue;
            }
            if (linha.startsWith("Total de saídas")) {
                direcaoCorrente = "debit";
                acumulador.setLength(0);
                continue;
            }

            Matcher amountMatcher = TRAILING_AMOUNT.matcher(linha);
            if (amountMatcher.find() && amountMatcher.end() == linha.length()) {
                String prefixo = linha.substring(0, amountMatcher.start()).trim();
                String descricao = (acumulador + " " + prefixo).trim().replaceAll("\\s+", " ");
                if (dataCorrente != null && direcaoCorrente != null && !descricao.isEmpty()) {
                    BigDecimal valor = parseValorBr(amountMatcher.group(1));
                    transacoes.add(toDto(dataCorrente, descricao, direcaoCorrente, valor));
                }
                acumulador.setLength(0);
            } else {
                if (!acumulador.isEmpty()) {
                    acumulador.append(' ');
                }
                acumulador.append(linha);
            }
        }
        return transacoes;
    }

    private BigDecimal parseValorBr(String raw) {
        return new BigDecimal(raw.replace(".", "").replace(',', '.'));
    }

    private NormalizedTransactionDTO toDto(LocalDate data, String descricao, String direcao, BigDecimal valor) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(valor, BigDecimal.ONE));
        fields.put("transaction_date", new StagedFieldValueDTO(data.toString(), BigDecimal.ONE));
        // Direção vem da seção corrente, não de sinal ambíguo no texto — confiança máxima.
        fields.put("direction", new StagedFieldValueDTO(direcao, BigDecimal.ONE));
        fields.put("description", new StagedFieldValueDTO(descricao, new BigDecimal("0.8")));
        return new NormalizedTransactionDTO(null, fields, null, null, BigDecimal.ONE, null, null);
    }
}
```

- [ ] **Step 4: Rodar o teste de `matches()`/`templateId()` — confirmar passa**

Run: `cd backend && ./mvnw test -Dtest=NubankExtratoTemplateTest`
Expected: PASS (3 testes do Step 1)

- [ ] **Step 5: Escrever os testes de `parse()` — linha simples, multilinha, direção por seção**

```java
    @Test
    void parseReconheceEntradaDeLinhaUnica() {
        String texto = "Movimentações\n"
                + "05 JUL 2026 Total de entradas + 4.708,35\n"
                + "Resgate RDB 4.708,35\n"
                + "Total de saídas - 0,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("transaction_date").value()).isEqualTo("2026-07-05");
        assertThat(tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("4708.35"));
        assertThat(tx.fields().get("description").value()).isEqualTo("Resgate RDB");
        assertThat(tx.fields().get("direction").value()).isEqualTo("credit");
    }

    @Test
    void parseReconheceSaidaComContraparteMultilinha() {
        String texto = "Movimentações\n"
                + "10 JUL 2026 Total de entradas + 0,00\n"
                + "Total de saídas - 593,21\n"
                + "Transferência enviada pelo Pix MERCADO PAGO INSTITUICAO DE PAGAMENTO\n"
                + "LTDA - 10.573.521/0001-91 - MERCADO PAGO IP\n"
                + "LTDA. (0323) Agência: 1 Conta: 1488917887-3\n"
                + "593,21\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(1);
        NormalizedTransactionDTO tx = transacoes.get(0);
        assertThat(tx.fields().get("amount").value()).isEqualByComparingTo(new BigDecimal("593.21"));
        assertThat(tx.fields().get("direction").value()).isEqualTo("debit");
        assertThat(tx.fields().get("description").value())
                .isEqualTo("Transferência enviada pelo Pix MERCADO PAGO INSTITUICAO DE PAGAMENTO "
                        + "LTDA - 10.573.521/0001-91 - MERCADO PAGO IP "
                        + "LTDA. (0323) Agência: 1 Conta: 1488917887-3");
    }

    @Test
    void parseDistingueDirecaoPelaSecaoCorrenteNaoPeloRotulo() {
        // "Resgate RDB" aparece nos dois lados — só a seção ("Total de entradas"/"saídas")
        // corrente decide a direção.
        String texto = "Movimentações\n"
                + "06 JUL 2026 Total de entradas + 250,00\n"
                + "Resgate RDB 250,00\n"
                + "Total de saídas - 250,00\n"
                + "Aplicação RDB 250,00\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        assertThat(transacoes).hasSize(2);
        assertThat(transacoes.get(0).fields().get("direction").value()).isEqualTo("credit");
        assertThat(transacoes.get(1).fields().get("direction").value()).isEqualTo("debit");
    }

    @Test
    void parseNaoGeraTransacaoParaAsLinhasDeSubtotal() {
        String texto = "Movimentações\n"
                + "05 JUL 2026 Total de entradas + 4.708,35\n"
                + "Resgate RDB 4.708,35\n"
                + "Total de saídas - 4.708,35\n"
                + "Transferência enviada pelo Pix FULANO DE TAL (Transferência enviada) 4.708,35\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto);

        // 2 transações reais (1 entrada, 1 saída) — as 2 linhas "Total de X" não contam.
        assertThat(transacoes).hasSize(2);
    }
```

- [ ] **Step 6: Rodar os testes de `parse()`, confirmar passam**

Run: `cd backend && ./mvnw test -Dtest=NubankExtratoTemplateTest`
Expected: PASS (7 testes no total)

- [ ] **Step 7: Validação manual contra o extrato real (não commitado)**

Mesmo procedimento da Task 2 (Step 7), com o extrato PDF Nubank real. Conferir
especialmente:
- Toda entrada/saída de cada dia aparece na lista (comparar a contagem e a soma por dia
  contra os valores de `"Total de entradas"`/`"Total de saídas"` impressos no próprio
  documento — se a soma não bater, uma transação foi perdida ou mesclada errado na
  descrição).
- Contrapartes multilinha viram uma única descrição legível, sem quebra de linha solta.
- Nenhuma linha de subtotal (`"Total de entradas"`/`"Total de saídas"`) aparece como
  transação.

Se a soma não bater para algum dia, é sinal de que uma entrada perdeu a linha de valor na
extração real (risco descrito na spec) — ajustar a state machine (ex.: tratar valor ausente
como fim de bloco pelo próximo cabeçalho de data/seção, não só pela própria linha de valor)
antes de prosseguir.

- [ ] **Step 8: Commit**

```bash
cd backend
git add src/main/java/com/fintech/api/service/imports/templates/NubankExtratoTemplate.java \
        src/test/java/com/fintech/api/service/imports/templates/NubankExtratoTemplateTest.java
git commit -m "$(cat <<'EOF'
feat(import): adiciona NubankExtratoTemplate ao registry de templates PDF

Reconhece transações da seção "Movimentações" do extrato PDF Nubank
via state machine linha a linha — data em linha própria, valor pode
ficar sozinho no fim de um bloco multilinha, direção vem da seção
"Total de entradas"/"Total de saídas" corrente (rótulos se repetem
nos dois lados, não servem para inferir direção sozinhos).
EOF
)"
```

---

### Task 4: Integração — `PdfTextExtractor` com os dois templates reais

**Files:**
- Modify: `backend/src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java`

**Interfaces:**
- Consumes: `ItauFaturaTemplate` (Task 2), `NubankExtratoTemplate` (Task 3), `PdfTextExtractor` (Task 1).

- [ ] **Step 1: Escrever os testes de integração**

Adicionar à classe `PdfTextExtractorTest` (import `com.fintech.api.service.imports.templates.ItauFaturaTemplate`
e `com.fintech.api.service.imports.templates.NubankExtratoTemplate`):

```java
    @Test
    void extractUsaTemplateItauQuandoConteudoBateAssinatura() {
        PdfTextExtractor comTemplates =
                new PdfTextExtractor("v1-test", List.of(new ItauFaturaTemplate(), new NubankExtratoTemplate()));
        String textoFatura = "FULANO DE TAL\nVencimento 10/03/2025\n"
                + "60.872.504/0001-23\n"
                + "Lançamentos: compras e saques\n"
                + "03/02 SUBWAY FAZENDINHA 49,00\n"
                + "Limites de crédito\n";

        NormalizedBatchDTO batch = comTemplates.extract(input(pdfComTexto(textoFatura.split("\n"))));

        assertThat(batch.extractorUsed()).isEqualTo("itau_fatura_v1");
        assertThat(batch.transactions()).hasSize(1);
    }

    @Test
    void extractUsaTemplateNubankQuandoConteudoBateAssinatura() {
        PdfTextExtractor comTemplates =
                new PdfTextExtractor("v1-test", List.of(new ItauFaturaTemplate(), new NubankExtratoTemplate()));
        String textoExtrato = "Fulano de Tal\n"
                + "18.236.120/0001-58\n"
                + "Movimentações\n"
                + "05 JUL 2026 Total de entradas + 4.708,35\n"
                + "Resgate RDB 4.708,35\n"
                + "Total de saídas - 0,00\n";

        NormalizedBatchDTO batch = comTemplates.extract(input(pdfComTexto(textoExtrato.split("\n"))));

        assertThat(batch.extractorUsed()).isEqualTo("nubank_extrato_v1");
        assertThat(batch.transactions()).hasSize(1);
    }

    @Test
    void extractCaiNaHeuristicaGenericaQuandoNenhumTemplateBate() {
        PdfTextExtractor comTemplates =
                new PdfTextExtractor("v1-test", List.of(new ItauFaturaTemplate(), new NubankExtratoTemplate()));

        NormalizedBatchDTO batch = comTemplates.extract(input(pdfComTexto("01/07/2026 PADARIA TESTE 55,90")));

        // Nenhum dos dois templates bate (sem CNPJ nem header conhecido) — cai na heurística
        // genérica, mesmo comportamento da fatia 1 (extractor_used = "pdf_text_v1").
        assertThat(batch.extractorUsed()).isEqualTo("pdf_text_v1");
        assertThat(batch.transactions()).hasSize(1);
    }
```

- [ ] **Step 2: Rodar os testes, confirmar passam**

Run: `cd backend && ./mvnw test -Dtest=PdfTextExtractorTest`
Expected: PASS (todos os testes da classe, incluindo os 3 novos)

- [ ] **Step 3: Rodar a suíte completa do backend — confirmar zero regressão em qualquer outro extrator**

Run: `cd backend && ./mvnw test`
Expected: PASS (suíte inteira verde — comando demora, rodar em background ou usar
`./scripts/test-summary.sh backend` conforme `commands.md`)

- [ ] **Step 4: Commit**

```bash
cd backend
git add src/test/java/com/fintech/api/service/imports/PdfTextExtractorTest.java
git commit -m "$(cat <<'EOF'
test(import): cobre PdfTextExtractor com os templates Itaú e Nubank reais

Confirma roteamento correto (extractor_used = templateId quando um
template bate) e zero regressão da heurística genérica quando nenhum
template reconhece o conteúdo.
EOF
)"
```

---

### Task 5: Documentação

**Files:**
- Modify: `summary.md`
- Modify: `docs/roadmap-extracao-e-conciliacao.md`

**Interfaces:** nenhuma (só documentação).

- [ ] **Step 1: Atualizar `summary.md` — seção de Importação**

Localizar o parágrafo que descreve `PdfTextExtractor` (busca por `PdfTextExtractor` em
`summary.md`) e adicionar, logo após a frase que descreve a heurística de linha, uma nota
sobre o registry:

```markdown
**Registry de templates (Fase 3, fatia 2):** antes da heurística genérica, o
`PdfTextExtractor` tenta templates por banco (`PdfBankTemplate`, mesmo padrão de lista de
beans ordenada do `VisionModelClient`) — detecção por CNPJ da instituição + rótulo de seção
conhecido no texto extraído (nunca por nome de arquivo). Hoje: `ItauFaturaTemplate`
(`itau_fatura_v1`, datas sem ano inferidas da data de vencimento, exclui "Compras
parceladas - próximas faturas" do mês corrente) e `NubankExtratoTemplate`
(`nubank_extrato_v1`, state machine linha a linha, direção pela seção "Total de
entradas"/"Total de saídas" corrente). Nenhum template bate → heurística genérica de linha
(fatia 1), inalterada. `extractor_used` grava o `templateId()` quando um template processa.
```

- [ ] **Step 2: Atualizar `docs/roadmap-extracao-e-conciliacao.md` — Fase 3**

Na seção "Fase 3", trocar a linha:
```markdown
- Registry de templates para os 2–3 bancos principais (definidos pelos dados da Fase 2) — cabeça da curva apenas
```
por:
```markdown
- Registry de templates para os 2–3 bancos principais (definidos pelos dados da Fase 2) — cabeça da curva apenas — **entregue, fatia 2**: Itaú (fatura PDF) e Nubank (extrato PDF). Nubank CSV não precisou de template — os headers reais já batem os sinônimos genéricos do `CsvExtractor` (Fase 2). CEF fica fora (só existe como print de imagem no caso avaliado — pertence a #194, não a registry de PDF/CSV)
```

Adicionar logo abaixo do parágrafo "**Fatia 1 entregue**" (já existente) um novo parágrafo:
```markdown
**Fatia 2 entregue** (spec `docs/superpowers/specs/2026-08-05-extracao-fase3-registry-templates-design.md`):
`PdfBankTemplate` (interface + lista de beans ordenada, mesmo padrão do `VisionModelClient`)
tentado antes da heurística genérica dentro do `PdfTextExtractor`; nenhum template bate →
heurística genérica inalterada. Dois templates: Itaú fatura (delimitação de seção +
inferência de ano pela data de vencimento) e Nubank extrato PDF (state machine, direção
pela seção "Total de entradas"/"Total de saídas" corrente). Fallback para IA em PDF não
reconhecido por template nem heurística segue fora de escopo — depende de PDF→imagem,
mesma pendência de "PDF escaneado via visão".
```

- [ ] **Step 3: Commit**

```bash
git add summary.md docs/roadmap-extracao-e-conciliacao.md
git commit -m "$(cat <<'EOF'
docs: documenta o registry de templates PDF (Itaú + Nubank) entregue

Atualiza summary.md (contrato/comportamento do PdfTextExtractor) e o
roadmap de extração (Fase 3, fatia 2 marcada como entregue).
EOF
)"
```

---

## Nota de execução — worktree e issue

Por `git-operator.md`: nenhuma feature é desenvolvida direto em `develop`. Antes de
executar a Task 1, criar branch+worktree a partir da `develop` atualizada (spec e este
plano já estão commitados nela):

```bash
cd ~/fintech-core
git pull origin develop
git worktree add -b feature/extracao-fase3-registry-templates \
    ~/fintech-core/.worktrees/extracao-fase3-registry-templates develop
cd ~/fintech-core/.worktrees/extracao-fase3-registry-templates
```

Abrir a issue no GitHub (sub-issue do épico #176) antes ou durante a Task 1 — referenciar o
número real nos commits/PR quando existir (esta versão do plano foi escrita antes da issue
ser criada).
