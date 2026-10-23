# Spec de Design: Verificação de Confiança de Campos Críticos em `deriveRequiresReview`

**Issue:** #242 — `fix: requires_review não considera confiança de data/direção/método na extração por visão`  
**Data:** 17 de Outubro de 2026  
**Status:** Pronto para Implementação  
**Autor:** Antigravity (Arquiteto)  
**Executor:** Claude Code CLI (Worktree isolada)

---

## 1. Contexto & Problema

Na extração por visão de comprovantes e extratos bancários (#241, ADR-007), os campos categóricos e de data passam por um pós-processamento determinístico (`VisionFieldNormalizer`). Quando o modelo LLM devolve um valor fora do vocabulário esperado que não pode ser reconhecido/normalizado, o extrator preserva o texto original para visualização do usuário, mas zera a confiança daquele campo (`confidence = 0.0`).

Contudo, `ImportService.deriveRequiresReview` atualmente só avalia:
1. A confiança agregada da transação (`overallConfidence < overallThreshold`, default 0.90)
2. A confiança do campo `amount` (`amountConfidence < amountThreshold`, default 0.95)

**Impacto:**
Se a confiança agregada for alta (ex.: 0.94) e o `amount` tiver alta confiança (ex.: 0.98), mas o campo `transaction_date` ou `direction` tiver recebido confiança 0.0 (por ser ilegível ou inválido), a transação é gravada com `requires_review = false`. Em uma aplicação financeira, lançar uma transação sem revisão com data incorreta ou direção invertida (despesa como receita) é um risco grave de integridade.

---

## 2. Decisões Arquiteturais

### 2.1. Inclusão de `transaction_date`
- A data é um campo mandatório para a persistência final de uma `Transaction` (o `commit()` lança exceção se for nula/inválida).
- Ausência de data ou confiança abaixo de `dateThreshold` (default `0.90`) deve obrigatoriamente forçar `requires_review = true`.
- Adiciona-se a propriedade configurável `import.review.date-threshold=0.90` em `application.properties`.

### 2.2. Decisão sobre `direction`
- **Análise de Risco:** Se `direction` for interpretado incorretamente, uma saída (EXPENSE) vira entrada (INCOME) ou vice-versa, dobrando o erro contábil.
- **Restrição de Compatibilidade:**
  - `CsvExtractor` e `PdfTextExtractor` inferem a direção por heurística determinística (sinal numérico ou palavra-chave), atribuindo intencionalmente confiança `0.70` (spec §183).
  - Se `directionThreshold` fosse 0.90, 100% das transações importadas via CSV ou PDF exigiriam revisão humana forçada (regressão inaceitável).
  - Alguns formatos ou fixtures de teste antigos não incluem o campo `direction` no mapa de `fields` (o `commit()` assume fallback para `EXPENSE`).
- **Decisão:**
  - Criar `import.review.direction-threshold=0.70` (default `0.70`).
  - Se `direction` **estiver presente** no mapa `fields` e sua confiança for estritamente menor que `directionThreshold` (ex.: `0.0` quando o normalizador de visão rejeitou o valor lido pelo modelo), força `requires_review = true`.
  - Transações de CSV/PDF com confiança `0.70` NÃO são marcadas como `requires_review` (pois `0.70 < 0.70` é falso).
  - Transações sem o campo `direction` (null no mapa) mantêm o comportamento existente sem serem bloqueadas.

### 2.3. Decisão sobre `payment_method`
- `payment_method` é um metadado complementar que não afeta saldos nem lançamento contábil. Muitas transações válidas não possuem método de pagamento explícito.
- Portanto, não deve forçar `requires_review = true` de forma autônoma.

---

## 3. Modificações Necessárias

### 3.1. `backend/src/main/resources/application.properties`
Adicionar os novos thresholds:
```properties
import.review.date-threshold=0.90
import.review.direction-threshold=0.70
```

### 3.2. `backend/src/main/java/com/fintech/api/service/imports/ImportService.java`
Injetar as properties:
```java
@Value("${import.review.date-threshold:0.90}")
private BigDecimal dateThreshold;

@Value("${import.review.direction-threshold:0.70}")
private BigDecimal directionThreshold;
```

Atualizar `deriveRequiresReview`:
```java
/**
 * Regra de review: exige olho humano se a confiança agregada não bate o threshold geral, OU
 * se a confiança de algum campo crítico (amount, transaction_date) não bate o respectivo threshold.
 * Ausência de confiança (null) conta como "duvidoso" → exige review (#242).
 *
 * <p>Decisão sobre direction (#242): se presente no mapa e com confiança estritamente abaixo
 * de {@code directionThreshold} (ex.: 0.0 quando a normalização de visão falha em reconhecer
 * o valor), exige revisão. Porém, CsvExtractor e PdfTextExtractor inferem direction por heurística
 * com confiança 0.70 por design (§183) — logo, o threshold default de direction é 0.70
 * (não bloqueia 0.70) e ausência de direction não bloqueia transações que não definem esse campo.
 */
private boolean deriveRequiresReview(Map<String, StagedFieldValue> fields, BigDecimal overallConfidence) {
    if (isBelow(overallConfidence, overallThreshold)) {
        return true;
    }
    StagedFieldValue amount = fields == null ? null : fields.get("amount");
    BigDecimal amountConfidence = amount == null ? null : amount.confidence();
    if (isBelow(amountConfidence, amountThreshold)) {
        return true;
    }
    StagedFieldValue date = fields == null ? null : fields.get("transaction_date");
    BigDecimal dateConfidence = date == null ? null : date.confidence();
    if (isBelow(dateConfidence, dateThreshold)) {
        return true;
    }
    StagedFieldValue direction = fields == null ? null : fields.get("direction");
    if (direction != null && isBelow(direction.confidence(), directionThreshold)) {
        return true;
    }
    return false;
}
```

### 3.3. `backend/src/main/java/com/fintech/api/service/imports/VisionExtractor.java`
Atualizar o comentário das linhas 625-628:
```java
 * <p>#242: o {@code deriveRequiresReview} do {@code ImportService} considera a confiança
 * de {@code transaction_date} e {@code direction} — logo, confiança 0.0 de valor não reconhecido
 * força {@code requires_review=true} automaticamente, mesmo se o overall do modelo for alto.
```

### 3.4. `summary.md`
Atualizar a seção correspondente em `summary.md` documentando a inclusão de `dateThreshold` e `directionThreshold`.

---

## 4. Testes Automatizados

### 4.1. Atualização em `ImportServiceTest.java`
1. Em `lowAmountConfidence()`, adicionar `"transaction_date", fieldValue("2026-06-28", "1.0")` para que a ausência de data não interfira no teste específico de patch de amount.
2. Adicionar novos métodos auxiliares:
   - `lowDateConfidence()`: overall 0.99, amount 0.99 (conf 0.99), date "sem-data" (conf 0.00).
   - `lowDirectionConfidence()`: overall 0.99, amount 0.99, date "2026-06-28" (conf 1.0), direction "saída" (conf 0.00).
3. Em `derivaRequiresReviewPorThresholdNoCodigo()`:
   - Testar que `lowDateConfidence()` resulta em `requiresReview = true`.
   - Testar que `lowDirectionConfidence()` resulta em `requiresReview = true`.
4. Criar teste específico:
   - `transacaoComDataOuDirecaoIlegivelNaVisaoExigeRevisaoMesmoComOverallAlto()` provando a integração da extração de visão com confianças 0.0 e a ativação de `requires_review = true`.
