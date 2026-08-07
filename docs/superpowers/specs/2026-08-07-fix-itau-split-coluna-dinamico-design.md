# Spec: split de coluna dinâmico + guard de soma na fatura Itaú

**Data:** 2026-08-07
**Status:** proposto (aguardando aprovação)
**Fonte:** validação manual contra fatura real em prod — total impresso R$15.860,53,
sistema importou só R$3.326,53 (43 de ~90+ transações esperadas), sem erro nem aviso.
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

`ItauFaturaTemplate` separa a página em duas colunas de lançamentos via
`PDFTextStripperByArea` com um corte fixo em `COLUMN_SPLIT_X = 365f` — constante medida
contra UM documento real anterior (comentário no código já avisava: "não há segundo
exemplar de fatura pra validar se varia entre documentos").

Nesta fatura real (multi-titular, 2 portadores), o cabeçalho `Lançamentos: compras e
saques` da coluna direita começa em X≈351–358 (varia por página) — **antes** do corte em
365. O texto do cabeçalho é cortado ao meio pela extração por região: a coluna direita
recebe só o sufixo (`çamentos: compras e saques`), a busca por string exata nunca acha, e
o bloco inteiro da coluna direita — a maior parte do valor da fatura — é descartado em
silêncio. Confirmado por análise manual, independente do sistema: 43 linhas reconhecidas
somando R$3.326,53, enquanto a fatura declara `Lançamentos no cartão` R$12.827,30 (titular
principal) + R$2.786,57 (segundo titular) = R$15.613,87 de lançamentos reais em
"compras e saques" (o total impresso R$15.860,53 inclui ainda produtos/serviços e
lançamentos internacionais, seções fora de escopo por design).

O guard de observabilidade existente (`log.warn` quando uma coluna tem linhas no formato
`DD/MM ... valor` mas zero transações reconhecidas) **não pegou o caso**: a corrupção do
split é severa o bastante pra que nem as linhas de transação da coluna direita sobrevivam
reconhecíveis como `DD/MM ...` — a condição do guard nunca fica verdadeira.

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Onde fixar o corte de coluna | Detecção dinâmica por página: maior vão horizontal entre extents de texto vira o corte | Manter constante fixa e só recalibrar o valor — resolve esta fatura, quebra na próxima com layout diferente (mesma classe de bug) |
| b | Guard de soma × total declarado | Interface `PdfBankTemplate` ganha método opcional `declaredTotal(fullText)`; `PdfTextExtractor` compara genericamente pós-parse | Guard só dentro do `ItauFaturaTemplate` — próximo template que precisar duplica a lógica |
| c | Ação quando soma diverge do total declarado | Não falha o batch — grava aviso visível (`extraction_warning`), batch segue `EXTRACTED` normalmente | Falhar o batch (`ExtractionException` → `FAILED`) — mais rígido, mas o usuário revisa cada linha antes de commitar de qualquer forma; aviso não-bloqueante já cobre o caso sem fricção extra |
| d | Âncora textual do total declarado (Itaú) | Soma todas as ocorrências de `Lançamentos no cartão {valor}` no texto (uma por titular/coluna — cobre fatura single e multi-titular igual) | `Total dos lançamentos atuais` — inclui produtos/serviços e internacionais (fora do escopo de "compras e saques" por design), gera falso positivo de divergência sempre que essas seções existirem |
| e | Tolerância da comparação | `R$0,05` (arredondamento) | Comparação exata — frágil a diferenças de centavo por truncamento de exibição |

**(a) Detecção dinâmica, não recalibração.** O problema não é o valor 365 estar errado —
é que QUALQUER constante fixa quebra assim que um documento tiver layout um pouco
diferente (múltiplos titulares, versão de template do banco, etc.). Detectar o vão real
por página generaliza sem precisar de um segundo exemplar pra calibrar.

**(b) Método opcional na interface, não acoplado ao Itaú.** Mesmo padrão de extensão já
usado no projeto (`VisionModelClient` — lista de beans, comportamento plugável). Nubank
não implementa por ora (`Optional.empty()` default) — sem quebrar o contrato nem forçar
implementação prematura.

**(c) Aviso, não falha.** Dado incompleto e AVISADO é estritamente melhor que dado
incompleto silencioso (o bug que motivou esta spec) — mas falhar o batch inteiro por uma
divergência de soma seria mais rígido do que o produto pede: o usuário já revisa cada
staged antes de commitar (Fase 1), o aviso só garante que ele saiba que a soma não fecha
antes de decidir.

## 3. Modelo de dados / Contrato de API

**Migration nova** (`V30`): `import_batches.extraction_warning VARCHAR(500)` nullable —
mesmo padrão de `failure_reason` (V25), mas para o caso "extraiu com sucesso, mas soma não
bate com o total declarado no documento". Nulável: só Itaú (por ora) declara total; todo
outro extrator segue `NULL`.

**`NormalizedBatchDTO`:** novo campo opcional `extractionWarning` (String), mesma posição
na cadeia de proveniência dos campos V28 (quem MEDE é o extrator — aqui, `PdfTextExtractor`
pós-comparação —, quem GRAVA é o `ImportService`).

**Contrato REST:** `ImportBatchResponseDTO` ganha `extractionWarning` (nullable, mesma
semântica de exibição de `failureReason` — mas não implica `status=FAILED`). Campo novo,
opcional, aditivo — **SemVer PATCH** (schema de resposta já tolera campo novo opcional sem
quebrar consumidor existente que ignora chaves desconhecidas do OpenAPI, mas o schema
OpenAPI precisa declarar o campo — verificar se `required` do DTO de resposta muda algo
consumido pelo frontend gerado via Orval).

## 4. Fluxo

### 4.1 Detecção dinâmica de coluna (`ItauFaturaTemplate`)

Por página, em vez de duas regiões fixas:

1. Extrai todas as posições X de início de cada trecho de texto da página (via hook de
   `PDFTextStripper`, não `PDFTextStripperByArea`).
2. Ordena os extents (`xStart`, `xEnd`) por `xStart`.
3. Acha o maior vão (`gap = próximoXStart − xEndAtual`) entre extents consecutivos, dentro
   de uma faixa plausível de conteúdo (ignora margem de borda da página).
4. Corte = ponto médio do maior vão.
5. Com o corte calculado, reusa `PDFTextStripperByArea` com as duas regiões (mesma
   mecânica atual) para extrair o texto de cada coluna — só o valor do corte passa a ser
   por página, não uma constante global.

Página sem vão claro (layout de coluna única, ex. a folha de resumo) → corte degenera pra
"tudo numa coluna só" (região direita vazia) — sem quebrar, só sem conteúdo pra
processar ali (comportamento já tolerado hoje pelo loop de blocos, que simplesmente não
acha `HEADER_LANCAMENTOS` numa coluna vazia).

### 4.2 Guard de soma (`PdfBankTemplate` + `PdfTextExtractor`)

```java
public interface PdfBankTemplate {
    boolean matches(String fullText);
    List<NormalizedTransactionDTO> parse(String fullText, byte[] content);
    String templateId();
    default Optional<BigDecimal> declaredTotal(String fullText) {
        return Optional.empty();
    }
}
```

`ItauFaturaTemplate.declaredTotal`: regex sobre todas as ocorrências de
`Lançamentos no cartão\s+(\d{1,3}(?:\.\d{3})*,\d{2})` no `fullText`, soma os valores
casados. Zero ocorrências → `Optional.empty()` (sem dado pra comparar, sem falso aviso).

`PdfTextExtractor`, após `template.parse(...)` ter sucesso:
```
declarado = template.declaredTotal(fullText)
se declarado presente:
    somaExtraida = soma(transacoes.amount)
    se |somaExtraida - declarado| > 0.05:
        extractionWarning = "Soma das transações extraídas (R$ X) não bate com o total
                              declarado na fatura (R$ Y) — revise antes de lançar."
```

`extractionWarning` viaja no `NormalizedBatchDTO` até `ImportService.createBatch`, que
grava na coluna nova. Batch segue `EXTRACTED` normalmente — commit funciona igual, sem
bloqueio.

### 4.3 Frontend — exibição do aviso

Mesmo padrão visual do card de falha (`failureReason`), mas ícone/cor de aviso (não erro)
— o batch não falhou, só pode estar incompleto. Exibido no header do batch na tela de
revisão, acima da tabela de staged.

## 5. Testes

| Camada | Cobertura |
|---|---|
| `ItauFaturaTemplateTest` | fixture sintética com 2 colunas onde o cabeçalho da direita começa ANTES do que seria um corte fixo ingênuo (reproduz o bug) — extração dinâmica reconhece ambas colunas corretamente; fixture de página com 1 coluna só (sem vão) não quebra, só não processa a região vazia |
| `ItauFaturaTemplateTest` | `declaredTotal`: soma múltiplas ocorrências de "Lançamentos no cartão" (multi-titular); zero ocorrências → `Optional.empty()` |
| `PdfTextExtractorTest` | soma extraída diverge do `declaredTotal` além da tolerância → `NormalizedBatchDTO.extractionWarning` preenchido; soma bate → `null`; template sem `declaredTotal` (Nubank) → `null`, sem exceção |
| `ImportServiceTest` | `createBatch` grava `extraction_warning` no batch quando presente; batch segue `EXTRACTED` (não `FAILED`) mesmo com aviso |
| Frontend (Vitest) | card do batch exibe aviso quando `extractionWarning != null`, distinto visualmente do card de falha |

Fixtures sintéticas — mesmo padrão já usado (nomes/valores fictícios, nunca dado real
desta investigação).

## 6. Impacto SemVer

**PATCH** — campo novo opcional em resposta já existente, nenhuma mudança de request,
migration aditiva (coluna nullable). Nenhum consumidor existente quebra.

## 7. Dívida técnica registrada

- `declaredTotal` só implementado no Itaú por ora — Nubank e futuros templates ficam sem
  o guard até declararem sua própria âncora textual de total (se existir no formato do
  banco).
- Página de layout de coluna única (resumo, capa) não tem vão detectável — hoje
  simplesmente não processa nada ali, o que é correto (não tem lançamento pra perder), mas
  não há teste explícito de "página sem vão detectável não derruba a extração inteira".
