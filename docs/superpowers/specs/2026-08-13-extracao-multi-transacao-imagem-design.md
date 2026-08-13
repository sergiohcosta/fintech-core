# Spec: Extração — multi-transação a partir de imagem única (print de extrato)

**Data:** 2026-08-13
**Status:** proposto (aguardando aprovação)
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 3 ("PDF, registry de
templates e a camada de cobertura universal"), entrega "Extração multi-transação por imagem
única"
**Issue:** #194 (sub-issue do épico **#176** — Fase 3)
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
**Relacionado:** #193 (guard-rail de curto prazo que esta spec substitui)
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

Processo multi-LLM (`/octo:embrace`): Discover (Codex + Claude) → Define (consenso 3
perspectivas) → **Debate gate Define→Develop: REVISE** (2 providers convergiram no mesmo
achado de código, independentemente) → decisões desta spec resolvem os 3 bloqueios do gate,
verificadas contra o código real (não a proposta original do consenso, que tinha erros).

## 1. Contexto e escopo

`VisionExtractor` hoje é 1:1 por desenho (`LlmReceiptExtractionDTO` plano — Fase 1, spec
`2026-07-24-extracao-fundacao-e-mvp-imagem-design.md`). #193 (entregue) detecta uma imagem
com múltiplos lançamentos (`multipleTransactionsDetected`) e **recusa explicitamente**
(`ExtractionException`) em vez de extrair a linha errada em silêncio. Essa spec generaliza o
extrator para processar de verdade o caso que #193 hoje recusa: print de extrato completo do
app do banco (não PDF — isso é o roadmap "Extração via visão para PDF escaneado", fatia
futura da Fase 3, fora desta spec).

**Por que Fase 3 é o momento certo:** já existe o guard-corpo transversal necessário
(validação de sanidade pós-extração, roadmap §1.5) e o precedente de multi-transação por
arquivo (CSV/OFX, Fase 2). O que falta é só o caminho de visão para lista.

**Escopo desta spec:**
- Novo DTO interno `LlmStatementExtractionDTO` (lista de linhas + totais declarados opcionais).
- Segunda chamada ao modelo (mesmo provider vencedor da 1ª) quando `multipleTransactionsDetected=true`.
- Validação de sanidade específica de lista: nº de linhas, reconciliação soma×total quando
  declarado, `requires_review` forçado.
- Generalização de `VisionModelClient.extract(...)` para aceitar tipo de resposta genérico.
- Teto de `maxOutputTokens` (gap real, não existe hoje em nenhum client de visão).
- Substituição do gate de recusa do #193 por aceite com revisão obrigatória.

**Fora de escopo (§11 detalha):** PDF escaneado multi-transação, enforcement de
`requires_review` no commit (gap pré-existente do sistema, não introduzido por esta spec —
ver §2 decisão f), reconciliação bancária cross-batch, dedup import×import inteligente
(Fase 4), telemetria dedicada além do que V28 já grava.

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Contrato de dados | `LlmStatementExtractionDTO` novo, com `declaredTotalDebits`/`declaredTotalCredits` separados (não um único total ambíguo) | Um campo `declaredTotalAmount` único — não diz se é soma bruta, líquida, débito ou crédito; reconciliação com `sum(lines)` daria falso positivo/negativo |
| b | Fluxo de chamada ao modelo | **Duas chamadas sequenciais ao MESMO client vencedor**: 1ª com o prompt/schema de comprovante já existente (inalterado); se `multipleTransactionsDetected=true`, 2ª chamada com prompt/schema de extrato | Um schema único sempre-lista (retiraria `LlmReceiptExtractionDTO`/#193 por completo) — regride o caminho de comprovante único já calibrado para 95% de precisão (critério de saída da Fase 1), sem necessidade: a maioria das imagens continua sendo comprovante único |
| c | Interface `VisionModelClient` | Generificar: `<T> T extract(String prompt, MimeType mimeType, Resource imageResource, Class<T> responseType, Integer maxOutputTokens)` | Método novo duplicado (`extractStatement`) nas duas implementações — ~40 linhas repetidas por client, mesma lógica de classificação de disponibilidade colada 2x |
| d | Guard-rail do #193 | Substituído por: aceite + `requires_review=true` incondicional em TODAS as linhas do batch, na primeira versão | Já confiar em threshold de confiança normal — zero dado de produção sobre acurácia do modelo em lista; blast radius maior (N transações erradas, não 1) |
| e | Teto de custo/saída | `maxOutputTokens` explícito **só na chamada de extrato** (`import.vision.statement.max-output-tokens`, default 4096) — chamada de comprovante permanece sem teto (comportamento inalterado, zero risco de regressão) | Aplicar teto nas duas chamadas — mudaria o caminho já calibrado da Fase 1 sem necessidade |
| f | Enforcement de `requires_review` no commit | **Fora de escopo desta spec** — documentado como gap pré-existente do sistema inteiro (nenhum extrator, incluindo CSV/OFX/comprovante, bloqueia commit por `requires_review`; é sinal de UI, não gate técnico) | Adicionar bloqueio de commit — mudança estrutural cross-cutting (toda a `ImportService.commit`), fora do pedido da issue #194, merece spec própria |

**(a) Totais separados, não um único ambíguo.**
O gate de debate (`embrace-gate-define-develop-1786628767.md`) apontou isso como bloqueador
em ambas as visões (codex e claude, convergentes): "declaredTotalAmount" não diz o que
representa. `declaredTotalDebits`/`declaredTotalCredits` (ambos nullable — a maioria dos
prints não mostra total nenhum) reconciliam contra `Σ debit`/`Σ credit` calculado das linhas,
cada um só quando presente. Sem `declaredNetTotal` (redundante — derivável de
`credit − debit`, terceiro campo só adicionaria mais uma chance de o modelo errar) e sem
`declaredTransactionCount` (a maioria dos extratos não imprime contagem; adicionar um campo
que o modelo teria que inventar quando ausente é pior que não ter o sinal).

**(b) Duas chamadas, não um schema unificado.**
Tecnicamente, o Spring AI vincula o JSON Schema de saída **antes** da chamada
(`.entity(Class)`) — o modelo não pode "trocar de schema no meio da resposta". Então as
únicas opções reais são: (i) um schema único sempre-lista para toda imagem, ou (ii) dois
schemas, escolhidos por uma decisão prévia. A spec original consensuada (`grasp-consensus`)
já reconhecia isso mas o gate temeu que (ii) "reintroduza confiança na mesma flag booleana
não confiável". Resolvido: aqui a flag só decide **qual prompt pedir a seguir** (decisão de
roteamento, sem custo financeiro se errar) — não decide se o dado final é confiável (isso é
sempre governado pela validação nova + `requires_review` forçado, independente de quão boa é
a flag). Falso positivo (comprovante único marcado como lista): a 2ª chamada extrai como
lista de 1 linha, sem perda de dado, só uma chamada extra. Falso negativo (extrato marcado
como comprovante único): mesmo modo de falha que já existe HOJE em #193 (fora do critério de
sucesso desta spec, rastreado como risco aceito, não regressão).

**(c) Generificar a porta, não duplicar.**
Só 2 implementações (`GeminiVisionClient`, `OllamaVisionClient`), ~90 linhas cada — a
generificação troca uma linha (`.entity(LlmReceiptExtractionDTO.class)` →
`.entity(responseType)`) e adiciona um `if (maxOutputTokens != null) options(...)`
condicional. Confirmado nos jars locais que ambos os providers expõem a API necessária:
`GoogleGenAiChatOptions.Builder.maxOutputTokens(Integer)` e
`OllamaChatOptions.Builder.numPredict(Integer)` — o gap "Spring AI 2.0.0-M2 não verificado"
que o Discover deixou aberto está **fechado**: os métodos existem no jar pinado
(`spring-ai-google-genai-2.0.0-M2.jar`, `spring-ai-ollama-2.0.0-M2.jar`).

**(d) `requires_review=true` incondicional — o que essa mitigação realmente cobre.**
Nem `commit()` nem a UI de revisão em lote bloqueiam commit por `requires_review` hoje (§2.f)
— é sinal, não trava. Ainda assim, tem valor real: o frontend já renderiza o badge por linha
(`import.html:208`, `@if (row.requiresReview)`) na tela de revisão que o usuário passa antes
de confirmar cada lote — forçar `true` garante que TODA linha do primeiro lançamento de
extrato apareça marcada para o usuário olhar, o que já é o mecanismo de segurança que
CSV/OFX/comprovante usam há duas fases. Não é enforcement técnico; é sinal correto no lugar
que já existe.

**Correção (achada só na implementação, não no design original do gate):** pra esse sinal
realmente chegar ao banco, `ImportService.createBatch` (linha ~252) precisa de um ajuste de
UMA linha — hoje ele **nunca lê** `tx.requiresReview()`, só deriva por confiança
(`deriveRequiresReview(fields, tx.overallConfidence())`). Sem esse ajuste, o `true` que o
`VisionExtractor` manda no DTO seria descartado silenciosamente e §6.4 ficaria inconsistente
com o código de verdade. Fix mínimo, "piso nunca teto", em UMA linha:
```java
.requiresReview(Boolean.TRUE.equals(tx.requiresReview()) || deriveRequiresReview(fields, tx.overallConfidence()))
```
Não-destrutivo: todo extrator existente (CSV/OFX/comprovante) manda `null` nesse campo hoje
— `Boolean.TRUE.equals(null)` é `false`, então o `||` cai inteiro no `deriveRequiresReview`
de sempre, comportamento bit-a-bit idêntico. Só o caminho novo de extrato passa `true`
explícito. Isso NÃO é o enforcement de commit descartado em §2.f (que seguiria bloqueando
ativamente); é só fazer o sinal persistir e chegar à UI que já existe — diferença entre
"gravar o dado" e "impedir a ação com base nele".

**(e) Teto só na chamada nova.**
Isolamento de risco: zero chance de o teto de tokens interferir no caminho de comprovante já
calibrado (Fase 1, 95% precisão). Dimensionado por `max-lines` (60 × ~50 tokens/linha +
overhead ≈ 4096).

**(f) Enforcement de commit é gap pré-existente, não desta spec.**
Verificado no código: `ImportService.commit()` (linha ~434-490) nunca lê
`staged.getRequiresReview()` — nem para bloquear, nem para exigir confirmação extra. Isso é
verdade para **todo** extrator hoje (CSV, OFX, comprovante único), não uma lacuna introduzida
por #194. Ampliar essa spec para adicionar um bloqueio de commit mudaria comportamento de
todo o pipeline de importação (blast radius: toda a `ImportService`, toda a UI de revisão em
lote), decisão estrutural própria que a issue #194 não pede. Registrado como débito técnico
conhecido — se o produto quiser um gate real, é spec própria.

## 3. Invariante inviolável — isolamento de tenant

Sem mudança. `VisionExtractor` continua sem conhecer tenant — recebe bytes, devolve
`NormalizedBatchDTO`. A segunda chamada ao modelo não introduz leitura/escrita de banco; todo
acesso continua exclusivo do `ImportService`, que já filtra por `user.getTenant()`. Staging
já é linha-a-linha com `tenant_id` denormalizado desde a Fase 0/2 — mais linhas por batch não
é caminho novo de leitura/escrita.

## 4. Modelo de dados

**Nenhuma migration nova.** `NormalizedBatchDTO`/`NormalizedTransactionDTO` (Fase 0) já
suportam N transações — nenhuma mudança de schema. Reconciliação soma×total (`declaredTotal*`
vs. `Σ lines`) é sinal **em memória**, usado só para decidir `requires_review`; não persiste
em coluna nova (schema growth em `import_batches` fica DIFERIDO — só se auditoria de
produção pedir depois, mesmo raciocínio do roadmap §1.5 para não construir preventivamente).

## 5. Contrato de API

**Nenhuma mudança de forma.** `POST /api/imports` já aceita imagem via `ExtractionRouter`; o
novo caminho é interno ao `VisionExtractor`. Ajuste de documentação apenas: `openapi.yaml`
passa a citar "print de extrato com múltiplos lançamentos" entre os casos suportados pelo
extrator de imagem.

## 6. Fluxo

### 6.1 DTO novo

```java
record LlmStatementExtractionDTO(
    List<StatementLine> lines,
    BigDecimal declaredTotalDebits,   // nullable — extrato pode não imprimir
    BigDecimal declaredTotalCredits,  // nullable
    Double overallConfidence
) {
    record StatementLine(
        BigDecimal amount, Double amountConfidence,
        String transactionDate, Double transactionDateConfidence,
        String description, Double descriptionConfidence,
        String direction, Double directionConfidence   // "debit" | "credit"
    ) {}
}
```

Sem `paymentMethod` por linha (raramente impresso em extrato, diferente do comprovante).
Sem `multipleTransactionsDetected` neste DTO — essa flag só existe no `LlmReceiptExtractionDTO`
da 1ª chamada; aqui já sabemos que é lista.

### 6.2 `VisionModelClient` generificado

```java
public interface VisionModelClient {
    <T> T extract(String prompt, MimeType mimeType, Resource imageResource,
                   Class<T> responseType, Integer maxOutputTokens);
    String providerId();
    String modelId();
}
```

`maxOutputTokens=null` → nenhuma `.options(...)` chamada (comportamento idêntico ao atual
para a chamada de comprovante). `maxOutputTokens` não-nulo → `GeminiVisionClient` usa
`GoogleGenAiChatOptions.builder().maxOutputTokens(n)`, `OllamaVisionClient` usa
`OllamaChatOptions.builder().numPredict(n)`.

### 6.3 `VisionExtractor` — duas chamadas

```
1ª chamada: winner = tenta clients em ordem (fallback só por disponibilidade, igual hoje)
            raw = winner.extract(PROMPT_COMPROVANTE, mime, image, LlmReceiptExtractionDTO.class, null)

Boolean.TRUE.equals(raw.multipleTransactionsDetected())?
  ├─ não/null → fluxo atual, inalterado (mapLine único, requires_review derivado como hoje)
  └─ sim → 2ª chamada, MESMO winner, sem fallback pra outro provider nesta chamada:
           statement = winner.extract(PROMPT_EXTRATO, mime, image,
                                       LlmStatementExtractionDTO.class, maxOutputTokens)
           → validação §6.4 → NormalizedBatchDTO com N transações, requires_review=true em todas
```

Falha na 2ª chamada (disponibilidade ou conteúdo) → `ExtractionException`, batch `FAILED`,
sem tentar outro provider — trocar de modelo no meio da extração misturaria leituras de
providers diferentes da mesma imagem; mais simples e mais seguro falhar explícito e deixar o
usuário tentar de novo (mesma filosofia de "erro explícito > erro silencioso").

### 6.4 Validação de sanidade da lista

- **Nº de linhas:** `1..60` (`import.vision.statement.max-lines`, default 60). Acima disso →
  `ExtractionException` ("recorte a imagem em partes menores") — checado DEPOIS da extração
  (não dá para saber a contagem antes de chamar o modelo).
- **`amount`:** normaliza para valor absoluto; se veio negativo, zera a confiança do campo
  (sinal ambíguo/conflitante com `direction` — não é erro estrutural, mas exige revisão).
  `amount` nulo/zero segue a regra central já existente do `ImportService`
  (`fields ausentes → confiança zero, valor preservado, nunca apagado`) — nenhuma linha é
  descartada.
- **`direction`:** mesma normalização que já existe hoje (`normalizeDirection` —
  desconhecido cai em `debit`) — **decisão consciente de manter consistente** com o resto do
  pipeline: `ImportService.commit` já trata qualquer direção não-"credit" como EXPENSE no
  momento do lançamento (comportamento pré-existente, não exclusivo desta spec). Adicionar
  uma regra "rejeita direção desconhecida" só para o caminho de extrato criaria uma
  inconsistência de comportamento entre extratores sem necessidade — a mitigação real é
  `requires_review=true` incondicional (§2.d), que já força olho humano em toda linha do
  primeiro lançamento de extrato, igual ou mais forte que uma rejeição pontual.
- **Reconciliação soma×total:** `Σ amount WHERE direction=debit` vs `declaredTotalDebits`
  (quando não-nulo) e `Σ amount WHERE direction=credit` vs `declaredTotalCredits` (quando
  não-nulo), tolerância `max(0.02, 0.01 × total_declarado)` por direção — absoluta para
  extratos pequenos, relativa (1%) para grandes (erro de arredondamento por linha acumula).
  Mismatch fora da tolerância: **não descarta nenhuma linha** (issue exige não-destrutivo) —
  loga WARN com o diff; já não muda `requires_review` (já é `true` incondicional nesta
  versão — reconciliação é sinal de log/telemetria, não gate adicional).
- **Total ausente (`null`):** pula a reconciliação — não é erro, é ausência de sinal (mesma
  filosofia do `null` em `multipleTransactionsDetected` do #193).
- **Zero linhas parseáveis:** `ExtractionException` — mesmo guard-corpo central do
  `ImportService` para "nenhuma transação aproveitável".

### 6.5 Prompt de extrato

Mesma língua/estrutura do prompt de comprovante (§ código atual), pedindo explicitamente:
listar CADA linha visível na ordem em que aparece; nunca promover saldo/total/cabeçalho a
linha de transação; não completar linha cortada/ilegível (usar confiança baixa, não inventar);
ler o valor absoluto e a direção separadamente (nunca inferir direção pelo sinal do valor);
não presumir ano fora do que está visível.

## 7. Frontend

**Nenhuma mudança funcional obrigatória.** `import.html:208` já renderiza o badge de
`requiresReview` por linha na revisão em lote (entregue na Fase 2 metade B) — um batch de
extrato com N linhas, todas `requiresReview=true`, aparece exatamente como um batch CSV com N
linhas de baixa confiança apareceria hoje. Ajuste cosmético opcional (avaliar na execução):
rótulo/`accept` do input de upload já aceita imagem genérica, nenhuma mudança necessária.

## 8. Testes

| Camada | Cobertura |
|---|---|
| `LlmStatementExtractionDTO`/mapeamento (puro) | N linhas → N `NormalizedTransactionDTO`; `amount` negativo normalizado com confiança zerada; linha sem `direction` reconhecível cai em `debit` (comportamento documentado, não regressão) |
| `VisionExtractor` — roteamento das 2 chamadas | `multipleTransactionsDetected=false/null` → comportamento atual inalterado, ZERO chamada extra (assert 1 única invocação do client mock); `=true` → 2ª chamada ao MESMO client, com `maxOutputTokens` setado |
| `VisionExtractor` — validação de lista | >60 linhas → `ExtractionException`; reconciliação soma×total dentro/fora da tolerância (mismatch não descarta linhas — assert `size()` igual); total ausente → pula reconciliação sem erro; zero linhas parseáveis → `ExtractionException` |
| `VisionExtractor` — `requires_review` | toda linha do batch de extrato sai com `requires_review=true`, independente de confiança |
| `GeminiVisionClient`/`OllamaVisionClient` | `maxOutputTokens=null` → nenhuma `.options()` chamada (regressão zero pro caminho de comprovante); não-nulo → `.options()` chamado com o valor certo |
| Regressão `VisionExtractorTest`/`ImportFailureReasonTest` existentes | Casos de `multipleTransactionsDetected=false` continuam verdes sem alteração. Casos que hoje testam a RECUSA (`MULTIPLE_TRANSACTIONS_MESSAGE`) são **reescritos** para testar o novo caminho de aceite — o comportamento antigo (recusar) deixa de existir por design (#193 é substituído, não mantido em paralelo) |
| Integração | upload de fixture "print de extrato" sintética (≥5 linhas, 1 com total declarado, 1 sem) → `GET /staged` retorna N linhas, todas `requiresReview=true` → `commit` cria N transações |

Fixtures sintéticas em `backend/src/test/resources/imports/` (nunca extrato real de
alguém) — imagens geradas programaticamente com texto simulando linhas de extrato.

## 9. Dataset de testes

Feature de backend sem tabela nova — sem obrigação de seed por si só. Avaliar na execução se
vale um batch de "extrato multi-transação" no seed `dev`, para o frontend ter material real
de revisão do novo caminho (mesma decisão adiada que a spec de PDF texto tomou).

## 10. Critérios de saída

Do issue #194 (mapeados aos critérios já escritos lá):
- [ ] Spec SDD escrita e aprovada — **esta spec**.
- [ ] Corpus de avaliação inclui prints reais de extrato (não só fixtures sintéticas) —
      depende de #191 (dataset de avaliação), pode ficar para depois do merge inicial.
- [ ] Guard-rail de #193 (recusa explícita) substituído — extrato deixa de ser "fora de
      escopo" e passa a ser processado, com revisão obrigatória.
- [ ] Zero regressão no caminho de comprovante único (Fase 1): mesma chamada, mesmo prompt,
      mesmo schema, nenhum `.options()` novo nesse caminho.
- [ ] Reconciliação soma×total funcionando quando o extrato declara total (log estruturado,
      sem falhar o batch).
- [ ] Nenhuma linha descartada silenciosamente em nenhum cenário (mismatch, direção
      desconhecida, campo ausente) — sempre confiança zerada + preservação do valor.

## 11. Fora de escopo

- **PDF escaneado multi-transação** (Fase 3, fatia futura) — reusa o mesmo
  `LlmStatementExtractionDTO`/prompt/validação quando a rasterização de PDF→imagem existir;
  não construído agora (trabalho de infraestrutura sem relação com o contrato de dados desta
  spec).
- **Enforcement técnico de `requires_review` no commit** (§2.f) — gap pré-existente
  sistêmico, não desta spec.
- **`declaredTransactionCount`** — sinal descartado por §2.a (a maioria dos extratos não
  imprime contagem).
- **Detecção de extrato truncado/paginado** sem total declarado — sem sinal algorítmico
  confiável; mitigado só pela revisão humana obrigatória (§2.d), não resolvido
  algoritmicamente nesta versão.
- **Promoção automática para confiança normal** (sair do modo "sempre revisão") — fica para
  quando houver volume real revisado + taxa de correção baixa (issue de tuning futura,
  medível via `extractor_used` com sufixo distinto, ex. `vision_statement_gemini_...`).
- **Reconciliação bancária cross-batch e dedup import×import inteligente** — Fase 4.

## 12. Riscos

| Risco | Mitigação |
|---|---|
| Falso negativo do `multipleTransactionsDetected` (extrato tratado como comprovante único) | Mesmo modo de falha que já existe hoje (#193); fora do critério de sucesso desta spec, aceito como risco pré-existente não-regredido |
| Custo/latência maior nas imagens que disparam a 2ª chamada (extrato de fato) | Teto de `maxOutputTokens` isolado só nessa chamada; nº de linhas capado em 60; imagem já passa pelo cap global de 10MB (`spring.servlet.multipart.max-file-size`, `application.properties:62` — confirmado no código, não um gap) |
| `requires_review=true` incondicional é só sinal de UI, não bloqueia commit tecnicamente | Documentado explicitamente (§2.f) como característica pré-existente do sistema inteiro, não lacuna introduzida aqui; se o produto quiser enforcement real, é spec própria |
| Direção desconhecida cai em `debit` por padrão (pode mascarar um crédito como despesa) | Comportamento consistente com o resto do pipeline (inclusive o commit já faz o mesmo default); mitigado pela revisão obrigatória, não por uma regra divergente só para este caminho |
| Reconciliação soma×total com tolerância não validada contra extratos reais | Fixtures sintéticas cobrem o comportamento; validar tolerância contra dado real fica registrado como aprendizado pendente na issue #194, mesmo padrão da Fase 3 fatia 2 (que só descobriu bug de ordem real contra documento real, não fixture) |
| Reescrever `VisionExtractorTest`/`ImportFailureReasonTest` pode esconder regressão no caminho antigo se mal migrado | Ordem de execução (§14) isola: primeiro garante 100% dos testes atuais de comprovante único verdes SEM tocar no prompt/schema deles, só depois adiciona o caminho novo |

## 13. Impacto SemVer

**MINOR** — `api-spec/openapi.yaml` ganha descrição de capacidade nova (retrocompatível,
nenhum campo removido/renomeado). Comportamento de #193 muda (recusa → aceite com revisão),
mas não é contrato de API — é comportamento interno do extrator, mesma rota/mesmo schema de
resposta do endpoint.

## 14. Ordem de execução sugerida

1. **Generificar `VisionModelClient.extract(...)`** (interface + `GeminiVisionClient` +
   `OllamaVisionClient`, com `maxOutputTokens` nullable) — sem mudar comportamento nenhum
   ainda (chamada existente passa `null`). Suíte verde antes de prosseguir — isola o risco de
   quebrar o caminho de comprovante único ANTES de qualquer lógica nova entrar.
2. **`LlmStatementExtractionDTO`** + prompt de extrato + mapeamento para
   `NormalizedTransactionDTO` (função pura, testável sem Spring).
3. **2ª chamada no `VisionExtractor`** gatilhada por `multipleTransactionsDetected=true` +
   validação de nº de linhas + `requires_review=true` incondicional. Testes do caminho novo.
4. **Reconciliação soma×total** (log estruturado, não bloqueia).
5. **Reescrever `VisionExtractorTest`/`ImportFailureReasonTest`** que hoje cobrem a recusa do
   #193, para cobrir o novo caminho de aceite.
6. **`openapi.yaml`** (descrição) — sem `api-sync.sh` necessário (nenhum schema de request/
   response muda).
7. **Documentação**: `summary.md` (seção de Importação), `docs/roadmap-extracao-e-conciliacao.md`
   (marcar entrega, citar #194 explicitamente na Fase 3), fechar issue #193 apontando para
   #194 como substituição.

## 15. Correção pós-implementação (code review)

Review antes do commit (subagente) achou um problema real: `mapStatementLine` hardcodeava
`overallConfidence=1.0` para toda linha de extrato, em vez de propagar
`statement.overallConfidence()` (a confiança agregada real do modelo). Isso não quebrava a
criação do batch (`requires_review=true` incondicional já cobre esse momento — §2.d), mas
desativava silenciosamente o floor no momento em que o usuário edita a staged:
`ImportService.patchStaged` RE-DERIVA `requires_review` a partir de
`deriveRequiresReview(fields, overallConfidence)`, e com `overallConfidence` sempre `1.0` o
ramo "overall < 0.90 → revisa" nunca dispara — sobra só a confiança do campo `amount`. Cenário
concreto: usuário corrige a *descrição* de uma linha de extrato (nunca toca valor/data/direção)
→ `requires_review` cai pra `false` mesmo que ninguém tenha verificado o resto da linha.

Corrigido: `extractStatement` calcula `clampConfidence(statement.overallConfidence())` uma vez
e propaga pra cada linha via `mapStatementLine(line, statementOverallConfidence)`. Testes novos
(RED confirmado antes do fix): `overallConfidenceBaixaDoExtratoPersisteParaAlimentarReDerivacaoNoPatch`
(`VisionExtractorTest`) e `editarCampoNaoRelacionadoNaoApagaRequiresReviewForcadoQuandoOverallEBaixo`
(`ImportServiceTest`, ponta a ponta via `patchStaged`).

**Diferido, não bloqueante** (mesmo review): teste de integração real (`ImportControllerTest`,
upload multipart → `ExtractionRouter` → commit) para o caminho de extrato — a cobertura hoje é
via unitários (`VisionExtractorTest` prova a saída do extrator, `ImportServiceTest` prova a
persistência do floor), que juntos aproximam a mesma garantia sem exercitar o roteamento HTTP
real. Fica como próximo passo, não bloqueia esta entrega.
