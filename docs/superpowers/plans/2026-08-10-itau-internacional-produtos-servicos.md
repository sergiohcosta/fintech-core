# Extração de internacional e produtos/serviços (Itaú) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ItauFaturaTemplate` passa a extrair as seções "Lançamentos: produtos e serviços"
(por transação) e "Lançamentos internacionais" (consolidado por fatura), fechando a diferença
de R$120,66 entre o total impresso e o importado, registrada como dívida técnica na spec de
ancoragem de fatura.

**Arquitetura:** a infraestrutura de separação de coluna (`detectColumnSplit`) já isola essas
2 seções sem ruído — comprovado rodando o algoritmo real contra a fatura de referência. A
lógica de "achar todos os blocos de um header, concatenados, cobrindo continuação entre
páginas" hoje está embutida em `extrairTransacoesDoStream` — vira um helper compartilhado
(`extrairBlocosConcatenados`), reusado pelas 3 seções. Produtos/serviços reusa o `parseLinha`
já existente (mesmo formato de linha: `DD/MM CÓDIGO [NN/NN] VALOR`) com um wrapper que faz
lookahead de 1 linha pra descrição e descarta o marcador de parcela. Internacional não reusa
`parseLinha` — busca direto a linha de subtotal (`Total lançamentos inter. em R$`) e gera 1
transação sintética por fatura.

**Tech Stack:** Java 21, PDFBox 3.0.7, JUnit 5 + AssertJ — fixtures sintéticas em memória
(mesmo padrão de `ItauFaturaTemplateTest.java`, escrita palavra-a-palavra via
`escreveLinhasPalavraAPalavra`/`pdfComDuasColunas`).

## Global Constraints

- Nenhuma fatura real entra no repositório — fixtures sintéticas reproduzindo os formatos
  medidos, como todo o arquivo de teste já faz.
- Produtos/serviços **nunca** popula `installment_number`/`installment_total` — mesmo quando a
  linha traz o marcador `NN/NN`, o campo fica ausente do `fields` map (não `null` dentro dele —
  ausente de verdade, mesmo padrão de `toDto` já usado pra compras e saques sem marcador).
- Internacional gera **no máximo 1** transação sintética por bloco/fatura, mesmo que a seção
  tenha múltiplos lançamentos reais dentro dela.
- Seção ausente (qualquer uma das duas) → lista vazia, **sem exceção** — mesma filosofia de
  "ausência de sinal não é erro" já usada em todo o `PdfTextExtractor`/`ItauFaturaTemplate`.
- `parse()` de compras e saques (comportamento já em produção) precisa continuar
  **byte-idêntico** depois da Task 1 — é um refactor puro antes de qualquer feature nova.
- SemVer: **MINOR** — extração de dado antes ignorado, sem mudança de contrato REST.
- Spec completa: `docs/superpowers/specs/2026-08-10-itau-internacional-produtos-servicos-design.md`.

---

### Task 1: Extrai `extrairBlocosConcatenados` — refactor sem mudança de comportamento

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java` (nenhum teste novo — a suíte existente é a regressão)

**Interfaces:**
- Produces: `private String extrairBlocosConcatenados(String stream, String header)` — usado
  pelas Tasks 2 e 3.

- [ ] **Step 1: Baseline — confirma a suíte verde ANTES do refactor**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS (todos os testes já existentes).

- [ ] **Step 2: Extrai o helper compartilhado**

Adicionar, logo antes de `extrairTransacoesDoStream` (linha ~391 do arquivo atual):

```java
    /**
     * Concatena todos os blocos de UM header dentro do stream — cobre continuação entre
     * páginas (o mesmo header reaparece; o loop trata como um novo bloco lógico do MESMO
     * conteúdo, encadenado). Devolve o texto entre o header e o próximo {@link #STOP_MARKERS},
     * excluindo o PRÓPRIO header da lista de parada — sem essa exclusão, a repetição do header
     * por continuação de página fecharia o bloco prematuramente (mesma armadilha já corrigida
     * pra compras e saques, agora generalizada pra qualquer seção).
     */
    private String extrairBlocosConcatenados(String stream, String header) {
        StringBuilder blocos = new StringBuilder();
        int cursor = 0;
        while (true) {
            int headerIdx = stream.indexOf(header, cursor);
            if (headerIdx < 0) {
                break;
            }
            int start = headerIdx + header.length();
            int stop = stream.length();
            for (String marker : STOP_MARKERS) {
                if (marker.equals(header)) {
                    continue;
                }
                int idx = stream.indexOf(marker, start);
                if (idx >= 0 && idx < stop) {
                    stop = idx;
                }
            }
            blocos.append(stream, start, stop).append('\n');
            cursor = stop;
        }
        return blocos.toString();
    }
```

- [ ] **Step 3: Refatora `extrairTransacoesDoStream` pra usar o helper**

Substituir o corpo do método (linhas ~398-443) por:

```java
    /**
     * Localiza TODOS os blocos "Lançamentos: compras e saques" dentro de UM stream já
     * separado por coluna (esquerda ou direita) e reconhece as transações de cada um.
     */
    private List<NormalizedTransactionDTO> extrairTransacoesDoStream(
            String stream, int mesVencimento, int anoVencimento) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        String bloco = extrairBlocosConcatenados(stream, HEADER_LANCAMENTOS);
        for (String linha : bloco.lines().toList()) {
            TransacaoItau transacao = parseLinha(linha.trim(), mesVencimento, anoVencimento);
            if (transacao != null) {
                transacoes.add(toDto(transacao));
            }
        }
        // Observabilidade: coluna com conteúdo que PARECE transação (linha "DD/MM ...") mas
        // zero transações reconhecidas é sinal de header ausente/quebrado nesta coluna — sem
        // isso o resultado só fica menor que o esperado, em silêncio (exatamente a classe de
        // erro "plausível mas errado" que este fix existe pra evitar).
        if (transacoes.isEmpty() && !stream.isBlank()
                && stream.lines().anyMatch(linha -> LINE_START_DATE.matcher(linha.trim()).matches())) {
            log.warn("ItauFaturaTemplate: coluna com linhas no formato de transação mas nenhuma "
                    + "transação reconhecida — possível header \"{}\" ausente ou quebrado nesta coluna.",
                    HEADER_LANCAMENTOS);
        }
        return transacoes;
    }
```

(A única mudança de comportamento observável é ZERO: a lógica de cursor/stop-marker é idêntica,
só movida pro helper. `HEADER_LANCAMENTOS` continua fora de `STOP_MARKERS`, então a exclusão-de-si-mesmo
dentro do helper é um no-op pra este chamador — comportamento hoje inalterado.)

- [ ] **Step 4: Regressão — confirma a suíte continua verde DEPOIS do refactor**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS — TODOS os testes, sem exceção (é a prova de que o refactor não mudou
comportamento nenhum).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java
git commit -m "refactor(import): extrai extrairBlocosConcatenados, reusável entre seções da fatura Itaú"
```

---

### Task 2: Extrai "Lançamentos: produtos e serviços" por transação

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: `extrairBlocosConcatenados(stream, header)` (Task 1), `parseLinha` e `toDto` (já
  existentes, reusados sem mudança de assinatura).
- Produces: nada consumido por outra task.

- [ ] **Step 1: Write the failing tests**

Adicionar ao final do arquivo de teste, antes do fechamento da classe:

```java
    @Test
    void parseReconheceProdutoServicoDeLinhaUnicaSemContinuacao() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "Limites de crédito",
                        "Lançamentos: produtos e serviços", "03/01 ENVIOMENS.AUTOMATICA 7,49",
                        "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        NormalizedTransactionDTO t = transacoes.stream()
                .filter(tx -> "7.49".equals(tx.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(t.fields().get("description").value()).isEqualTo("ENVIOMENS.AUTOMATICA");
        assertThat(t.fields()).doesNotContainKey("installment_number");
    }

    @Test
    void parseReconheceProdutoServicoComDescricaoDaLinhaDeContinuacao() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "Limites de crédito",
                        "Lançamentos: produtos e serviços", "04/07 ANUIDADE DIFER 03/12 62,00",
                        "Anuidade Diferenciada", "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        NormalizedTransactionDTO t = transacoes.stream()
                .filter(tx -> "62.00".equals(tx.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        // Descrição vem da linha de CONTINUAÇÃO, não do código truncado da linha 1.
        assertThat(t.fields().get("description").value()).isEqualTo("Anuidade Diferenciada");
        // Marcador 03/12 estava presente na linha — mas produtos/serviços NUNCA vira parcela
        // (decisão c da spec: taxa recorrente, não compra parcelada).
        assertThat(t.fields()).doesNotContainKey("installment_number");
        assertThat(t.fields()).doesNotContainKey("installment_total");
    }

    @Test
    void parseReconheceEstornoDeProdutoServicoComoCredito() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "Limites de crédito",
                        "Lançamentos: produtos e serviços", "13/07 Redução Mensalidade do - 31,00",
                        "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        NormalizedTransactionDTO t = transacoes.stream()
                .filter(tx -> "31.00".equals(tx.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(t.fields().get("direction").value()).isEqualTo("credit");
    }

    @Test
    void parseNaoQuebraQuandoSecaoDeProdutosEServicosAusente() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "03/02 SUBWAY FAZENDINHA 49,00",
                        "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        // Compras e saques inalterado (regressão) — nenhuma transação extra, sem exceção.
        assertThat(transacoes).hasSize(1);
        assertThat(transacoes.get(0).fields().get("description").value()).isEqualTo("SUBWAY FAZENDINHA");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest#parseReconheceProdutoServicoDeLinhaUnicaSemContinuacao+parseReconheceProdutoServicoComDescricaoDaLinhaDeContinuacao+parseReconheceEstornoDeProdutoServicoComoCredito`
Expected: FAIL — `NoSuchElementException` (nenhuma transação com esses valores existe ainda; a
seção nunca é extraída). `parseNaoQuebraQuandoSecaoDeProdutosEServicosAusente` já passa hoje
(não é regressão nova, é baseline) — rodar mesmo assim pra confirmar que continua passando
depois.

- [ ] **Step 3: Implementar**

Adicionar a constante do header (junto de `HEADER_LANCAMENTOS`, linha ~47), e trocar a string
literal correspondente em `STOP_MARKERS` (linha ~57) pela constante:

```java
    private static final String HEADER_LANCAMENTOS = "Lançamentos: compras e saques";
    private static final String HEADER_PRODUTOS_SERVICOS = "Lançamentos: produtos e serviços";
```

```java
    private static final List<String> STOP_MARKERS = List.of(
            "Compras parceladas - próximas faturas",
            "Limites de crédito",
            "Encargos cobrados",
            "Lançamentos internacionais",
            HEADER_PRODUTOS_SERVICOS);
```

Adicionar o método novo, logo após `extrairTransacoesDoStream`:

```java
    /**
     * Extrai "Lançamentos: produtos e serviços" por transação — reusa {@link #parseLinha}
     * (mesmo formato de linha 1: DD/MM CÓDIGO [NN/NN] VALOR) mas com 2 diferenças: (1) espia a
     * PRÓXIMA linha do bloco — se não for início de outra transação, é a descrição completa
     * (o código da linha 1 costuma vir truncado, ex. "ANUIDADE DIFER" para "Anuidade
     * Diferenciada"); (2) NUNCA populada installment_number/total — decisão (c) da spec:
     * "Anuidade Diferenciada NN/12" é uma taxa recorrente que no corpus real é cobrada e
     * estornada quase no mês seguinte, não uma compra parcelada — criar um InstallmentGroup de
     * 12 parcelas projetaria cobranças futuras que o histórico mostra que não acontecem.
     */
    private List<NormalizedTransactionDTO> extrairProdutosEServicos(
            String stream, int mesVencimento, int anoVencimento) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        String bloco = extrairBlocosConcatenados(stream, HEADER_PRODUTOS_SERVICOS);
        List<String> linhas = bloco.lines().toList();
        for (int i = 0; i < linhas.size(); i++) {
            TransacaoItau base = parseLinha(linhas.get(i).trim(), mesVencimento, anoVencimento);
            if (base == null) {
                continue;
            }
            String descricao = base.descricao();
            if (i + 1 < linhas.size()) {
                String proxima = linhas.get(i + 1).trim();
                if (!proxima.isEmpty() && !LINE_START_DATE.matcher(proxima).matches()) {
                    descricao = proxima;
                }
            }
            transacoes.add(toDto(new TransacaoItau(base.data(), descricao, base.valor(), null, null)));
        }
        return transacoes;
    }
```

Em `parse()`, logo após as 2 chamadas existentes de `extrairTransacoesDoStream` (linhas ~149-150):

```java
        transacoes.addAll(extrairProdutosEServicos(colunaEsquerda.toString(), mesVencimento, anoVencimento));
        transacoes.addAll(extrairProdutosEServicos(colunaDireita.toString(), mesVencimento, anoVencimento));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS — TODOS, incluindo os pré-existentes (compras e saques inalterado).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "feat(import): ItauFaturaTemplate extrai produtos e serviços por transação"
```

---

### Task 3: Extrai "Lançamentos internacionais" consolidado

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java`
- Test: `backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java`

**Interfaces:**
- Consumes: `extrairBlocosConcatenados(stream, header)` (Task 1). Independente da Task 2 (não
  reusa `extrairProdutosEServicos` nem `parseLinha`).
- Produces: nada consumido por outra task — fecha o plano.

- [ ] **Step 1: Write the failing tests**

Adicionar ao final do arquivo de teste:

```java
    @Test
    void parseCriaTransacaoSinteticaConsolidadaParaLancamentosInternacionais() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "Limites de crédito",
                        "Lançamentos internacionais",
                        "DATA ESTABELECIMENTO US$ R$",
                        "18/07 ANTHROPIC* CLAUDE SUBSA 116,58",
                        "Total transações inter. em R$ 116,58",
                        "Repasse de IOF em R$ 4,08",
                        "Total lançamentos inter. em R$ 120,66",
                        "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        NormalizedTransactionDTO t = transacoes.stream()
                .filter(tx -> "120.66".equals(tx.fields().get("amount").value().toString()))
                .findFirst().orElseThrow();
        assertThat(t.fields().get("description").value())
                .isEqualTo("Lançamentos internacionais (consolidado)");
        // CABECALHO_VENCIMENTO = "Vencimento 10/03/2025" (mesVencimento=3, anoVencimento=2025).
        // Transação em 18/07: mês(7) > mesVencimento(3) → pertence ao ano ANTERIOR (mesma regra
        // já usada em parseLinha, ex. vencimento 10/03/2025 + lançamento 28/11 = 28/11/2024).
        assertThat(t.fields().get("transaction_date").value()).isEqualTo("2024-07-18");
        assertThat(t.fields()).doesNotContainKey("installment_number");
    }

    @Test
    void parseNaoCriaTransacaoQuandoSecaoInternacionalAusente() {
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "03/02 SUBWAY FAZENDINHA 49,00",
                        "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes)
                .noneMatch(tx -> "Lançamentos internacionais (consolidado)"
                        .equals(tx.fields().get("description").value()));
    }

    @Test
    void parseNaoCriaTransacaoQuandoSubtotalInternacionalNaoReconhecido() {
        // Header presente, mas sem a linha de subtotal no formato esperado — degrada em
        // silêncio (ausência de sinal não é erro), não lança exceção.
        byte[] pdfBytes = pdfComDuasColunas(
                List.of("Lançamentos: compras e saques", "Limites de crédito",
                        "Lançamentos internacionais", "algum texto sem o subtotal reconhecível",
                        "Limites de crédito"),
                List.of(), 50f, 400f);
        String texto = CABECALHO_VENCIMENTO + "Lançamentos: compras e saques\n";

        List<NormalizedTransactionDTO> transacoes = template.parse(texto, pdfBytes);

        assertThat(transacoes)
                .noneMatch(tx -> "Lançamentos internacionais (consolidado)"
                        .equals(tx.fields().get("description").value()));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest#parseCriaTransacaoSinteticaConsolidadaParaLancamentosInternacionais`
Expected: FAIL — `NoSuchElementException` (nenhuma transação com esse valor existe ainda). Os
outros 2 testes deste passo já passam hoje (baseline) — rodar mesmo assim.

- [ ] **Step 3: Implementar**

Adicionar as constantes (junto de `HEADER_PRODUTOS_SERVICOS`):

```java
    private static final String HEADER_INTERNACIONAL = "Lançamentos internacionais";
```

Trocar a string literal correspondente em `STOP_MARKERS` pela constante:

```java
    private static final List<String> STOP_MARKERS = List.of(
            "Compras parceladas - próximas faturas",
            "Limites de crédito",
            "Encargos cobrados",
            HEADER_INTERNACIONAL,
            HEADER_PRODUTOS_SERVICOS);
```

Adicionar os patterns novos (junto dos outros `Pattern` estáticos, próximo a `TRAILING_AMOUNT`):

```java
    // Linha de subtotal do bloco internacional — já soma o valor em R$ de todos os
    // lançamentos, incluindo IOF. Tolerante a espaço duplo entre "lançamentos" e "inter."
    // (variante observada no corpus real).
    private static final Pattern TOTAL_INTERNACIONAL =
            Pattern.compile("Total lançamentos\\s+inter\\. em R\\$\\s*(\\d[\\d.,]*)");
    // Primeira ocorrência de DD/MM dentro do bloco — usada como data representativa da
    // transação sintética consolidada (spec: decisão a).
    private static final Pattern PRIMEIRA_DATA_DO_BLOCO = Pattern.compile("(\\d{2})/(\\d{2})");
```

Adicionar o método novo, logo após `extrairProdutosEServicos`:

```java
    /**
     * "Lançamentos internacionais" vira NO MÁXIMO 1 transação sintética por fatura — decisão
     * (a) da spec: o valor em US$/conversão vem numa segunda linha sem mapeamento confiável
     * (amostra pequena, 9/21 faturas no corpus), mas a linha de subtotal já soma tudo certo,
     * incluindo IOF. Ausência da seção OU do subtotal reconhecível → lista vazia, sem erro
     * (mesma filosofia do resto do template: ausência de sinal não é erro).
     */
    private List<NormalizedTransactionDTO> extrairInternacionalConsolidado(
            String stream, int mesVencimento, int anoVencimento) {
        String bloco = extrairBlocosConcatenados(stream, HEADER_INTERNACIONAL);
        Matcher totalMatcher = TOTAL_INTERNACIONAL.matcher(bloco);
        if (!totalMatcher.find()) {
            return List.of();
        }
        Matcher dataMatcher = PRIMEIRA_DATA_DO_BLOCO.matcher(bloco.substring(0, totalMatcher.start()));
        if (!dataMatcher.find()) {
            return List.of();
        }
        int dia = Integer.parseInt(dataMatcher.group(1));
        int mes = Integer.parseInt(dataMatcher.group(2));
        int ano = mes > mesVencimento ? anoVencimento - 1 : anoVencimento;
        LocalDate data;
        try {
            data = LocalDate.of(ano, mes, dia);
        } catch (DateTimeException e) {
            return List.of();
        }
        BigDecimal valor = parseValorBr(totalMatcher.group(1));
        TransacaoItau t = new TransacaoItau(
                data, "Lançamentos internacionais (consolidado)", valor, null, null);
        return List.of(toDto(t));
    }
```

Em `parse()`, logo após as chamadas de `extrairProdutosEServicos` adicionadas na Task 2:

```java
        transacoes.addAll(extrairInternacionalConsolidado(colunaEsquerda.toString(), mesVencimento, anoVencimento));
        transacoes.addAll(extrairInternacionalConsolidado(colunaDireita.toString(), mesVencimento, anoVencimento));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=ItauFaturaTemplateTest`
Expected: PASS — TODOS, incluindo Task 1/2 e os pré-existentes.

- [ ] **Step 5: Full backend suite**

Run: `cd backend && ./mvnw test` (background, >7min — ver gotcha do CLAUDE.md). Confirma que
`PdfTextExtractorTest` (que instancia `ItauFaturaTemplate` real em alguns testes) e
`ImportServiceTest` continuam verdes — nenhum outro teste depende do número exato de
transações que o template devolve pra fixtures existentes.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/imports/templates/ItauFaturaTemplate.java \
        backend/src/test/java/com/fintech/api/service/imports/templates/ItauFaturaTemplateTest.java
git commit -m "feat(import): ItauFaturaTemplate extrai lançamentos internacionais (consolidado por fatura)"
```

---

## Verificação final (fora do plano de tasks — manual, pós-merge)

Reimportar a fatura de referência real (a mesma usada em toda esta investigação) numa conta de
teste em dev. Critério de sucesso: total importado sobe de R$15.739,87 pra próximo de
R$15.860,53 (o total impresso) — a diferença residual esperada é só o que o corpus não cobriu
(formato de subtotal não reconhecido em alguma variante, se houver). Limpar a conta de teste
depois.
