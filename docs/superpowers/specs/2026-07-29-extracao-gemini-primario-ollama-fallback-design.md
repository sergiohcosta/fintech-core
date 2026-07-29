# Spec: Extração por visão — Gemini como provider primário, Ollama como fallback

**Data:** 2026-07-29
**Status:** proposto (aguardando aprovação)
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` — camada de cobertura universal (IA)
**Spec anterior:** `docs/superpowers/specs/2026-07-24-extracao-fundacao-e-mvp-imagem-design.md` (Fases 0 e 1)
**Issue:** #197 · **Épico raiz:** #154 — extração multi-mídia e conciliação de transações
**Plano de execução:** `docs/superpowers/plans/2026-07-29-extracao-gemini-primario-ollama-fallback.md`
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

---

## 1. Contexto e problema

A Fase 1 entregou o `VisionExtractor`: um adaptador sobre o `ChatClient` do Spring AI que hoje
fala com **um** provider — o Ollama do homelab, modelo `llama3.2-vision`, escolhido por caber nos
11GB de VRAM da GPU local.

Essa escolha resolveu o problema certo na época (provar o pipeline ponta a ponta sem custo e sem
dependência externa), mas carrega três limitações que já se manifestam:

1. **Qualidade de OCR estrutural.** Um modelo de visão de 11B rodando quantizado erra mais na
   leitura de dígitos e datas de comprovante do que um Flash de fronteira. Cada erro vira
   confiança baixa → `requiresReview=true` → trabalho manual do usuário, que é exatamente o que
   a feature existe para eliminar.
2. **Latência.** O homelab serializa requisições numa GPU só; o usuário espera no spinner.
3. **Disponibilidade.** Se o homelab está desligado ou fora da rede, a extração por imagem
   simplesmente não existe — não há degradação graciosa, só batch `FAILED`.

Ao mesmo tempo, o Ollama tem uma propriedade que nenhum provider gerenciado tem: **é ilimitado e
gratuito**. O tier free do Gemini (Google AI Studio) é generoso mas finito (ordem de 1.500
requisições/dia, ~10-15 RPM na família Flash em jul/2026).

**Problema a resolver:** usar o Gemini como caminho primário (qualidade + latência) sem perder o
Ollama como rede de segurança quando a cota free acaba ou o provider gerenciado falha.

### 1.1 Por que isso não é só "trocar o starter"

O javadoc do `VisionAiConfig` e da porta `TransactionExtractor` afirmam hoje: *"trocar de provider
é trocar o starter Maven + properties, sem tocar em código"*. **Isso é verdade para trocar A por
B, e falso para ter A e B simultaneamente.** Duas consequências concretas:

- O `ExtractionRouter` escolhe extrator por **tipo de arquivo** (`supports()` por magic bytes) e
  para no primeiro que reconhece. Ele não tem — e não deve ganhar — o conceito de "dois extratores
  para o mesmo `sourceType` com fallback entre si": isso confundiria dois eixos ortogonais
  (*que formato é este?* vs. *qual modelo atende?*).
- A auto-configuração do Spring AI materializa `ChatClient.Builder` a partir de um **único**
  `ChatModel` candidato. Com dois starters no classpath há dois `ChatModel` beans e o `ChatClient`
  singleton do `VisionAiConfig` deixa de ser construível sem qualificação explícita.

Ou seja: multi-provider é uma decisão de arquitetura, não de configuração. É por isso que existe
esta spec.

---

## 2. Escopo

**Dentro:**
- Nova porta interna `VisionModelClient` (provider de visão) com duas implementações: Gemini e Ollama.
- Cadeia de fallback ordenada dentro do `VisionExtractor`, com política explícita de *quando* cair
  para o próximo provider.
- Configuração por properties/env (chave, modelo, ordem, on/off), sem chave commitada.
- **Proveniência estruturada e persistida** (§5.1): provider, modelo, latência e ocorrência de
  fallback como colunas de primeira classe em `import_batches`, para auditoria e estatística.

**Fora:**
- Multi-provider para CSV/OFX — são parsers determinísticos, não têm provider.
- Balanceamento/roteamento por custo, cache de resposta, ou contagem própria de cota (§7).
- Multi-transação por imagem (#194, Fase 3) — o guarda-corpo de recusa continua como está.
- Trocar o prompt ou o `LlmReceiptExtractionDTO`. O mesmo prompt roda nos dois providers
  (§5.3 trata da assimetria de qualidade que isso pode expor).

---

## 3. Decisão de design

### 3.1 A porta nova fica *dentro* do extrator, não ao lado dele

```
ExtractionRouter          (escolhe por FORMATO — inalterado)
  └── VisionExtractor     (único extrator de IMAGE — dono do prompt, do guarda-corpo e do fallback)
        └── List<VisionModelClient>   ← porta NOVA, ordenada
              ├── GeminiVisionClient   @Order(10)   — condicional à API key
              └── OllamaVisionClient   @Order(20)   — sempre presente
```

```java
public interface VisionModelClient {
    LlmReceiptExtractionDTO extract(String prompt, MimeType mimeType, Resource image);
    String providerId();   // "gemini", "ollama" — entra no extractorUsed
    String modelId();      // modelo efetivo, pra proveniência
}
```

**O que a porta devolve é o DTO CRU do modelo**, não o `NormalizedBatchDTO`. Esse detalhe é a
razão principal de a fronteira estar aqui e não um nível acima, e vale explicitar:

> O guarda-corpo de plausibilidade (`toNormalizedBatch`: recusa multi-transação, valida `amount`,
> normaliza data/direção) roda **uma única vez, depois** da cadeia. Assim ele fica estruturalmente
> fora da lógica de fallback — e é impossível escrever, por acidente, um código que tente o Ollama
> porque o Gemini disse "esta imagem é um extrato".

Alternativas descartadas:

| Alternativa | Por que não |
|---|---|
| Dois `TransactionExtractor` para `IMAGE` + fallback no `ExtractionRouter` | Mistura "que formato é este?" com "qual modelo atende?". O router é um funil de reconhecimento; dar a ele semântica de retry o transforma em orquestrador e contamina CSV/OFX com um conceito que não os afeta. |
| Spring Retry / `@Retryable` sobre o `ChatClient` | Retry repete a **mesma** chamada. Cota esgotada não se resolve repetindo; e o `spring.ai.retry.max-attempts=2` atual já cobre a falha transitória dentro de um provider. São problemas diferentes em camadas diferentes. |
| Um `ChatClient` só, trocando o `ChatModel` em runtime | Exige reconstruir o client por requisição e perde a configuração específica de cada provider (`num-ctx` do Ollama, `format=json`, etc.). Ganha nada. |

### 3.2 Política de fallback: falha de *disponibilidade* cai; falha de *conteúdo* não

Esta é a regra central da feature. Um `catch (Exception)` genérico — que é o que o
`VisionExtractor` tem hoje — seria **ativamente nocivo** aqui: transformaria "a imagem está
ilegível" em "tenta no outro modelo", pagando latência dobrada para chegar à mesma conclusão e,
pior, mascarando o `failureReason` correto que o batch `FAILED` passou a carregar em #193.

| Situação | Classificação | Ação |
|---|---|---|
| HTTP 429 (`RESOURCE_EXHAUSTED`) | cota/rate limit | **fallback**, log WARN |
| HTTP 5xx, timeout, `IOException`, DNS | provider indisponível | **fallback**, log WARN |
| HTTP 401/403 (chave inválida/revogada) | config quebrada | **fallback**, log **ERROR** |
| HTTP 400, resposta não parseável no schema | provider recusou o *input* | **fallback**, log WARN |
| `ExtractionException` do guarda-corpo (multi-transação, `amount` inválido) | conteúdo | **não** faz fallback — propaga |
| Nenhum provider restante | — | `ExtractionException` com motivo do **último** erro |

Duas escolhas nessa tabela merecem defesa:

- **401/403 faz fallback, mas loga ERROR.** Chave errada é problema operacional meu, não do
  usuário — degradar para o Ollama é melhor UX do que falhar. Mas silenciar seria pior ainda:
  o sistema rodaria meses "funcionando" no fallback sem ninguém notar que o primário está morto.
  O ERROR é o que torna a degradação *visível* em vez de *silenciosa*.
- **HTTP 400 faz fallback.** Um provider pode rejeitar um formato/tamanho de imagem que o outro
  aceita. Como o guarda-corpo de conteúdo já está isolado (§3.1), aqui não há risco de mascarar
  decisão de negócio — no pior caso gasta-se uma tentativa extra.

### 3.3 O Gemini se auto-desliga na ausência de chave

`GeminiVisionClient` é `@ConditionalOnProperty(spring.ai.google.genai.api-key)`. Sem chave, o bean
não existe, a lista tem só o Ollama, e o comportamento é **bit a bit o de hoje**. Isso não é
detalhe de conveniência: é o que mantém a suíte de testes, o `docker compose` local e qualquer
clone novo do repo funcionando sem segredo nenhum — e o que torna a mudança reversível em
produção virando uma env var, sem redeploy de código.

---

## 4. Modelo Gemini: qual usar

**Recomendação: a família Flash mais recente disponível no tier free do Google AI Studio**
(em jul/2026, `Gemini 3.x Flash`), com o ID exato **confirmado no AI Studio no momento da
implementação** e configurado via env var — nunca como constante em código.

Racional:
- **Flash, não Pro:** a tarefa é ler ~6 campos de um recibo, não raciocinar. Pro custa cota
  (é o recurso escasso aqui, não o dinheiro) e entrega ganho marginal.
- **Flash, não Flash-Lite:** Lite é a válvula de escape se a cota apertar, não o default —
  precisão de OCR estrutural é justamente onde o Lite economiza.
- **ID por env var, obrigatoriamente.** O `gemini-2.0-flash` foi aposentado em março/2026. Um ID
  de modelo é um recurso com prazo de validade; assá-lo no código garante um dia de produção
  quebrada por deprecação anunciada meses antes. O Ollama já segue esse padrão
  (`${OLLAMA_MODEL:...}`) — o Gemini herda a mesma disciplina.

Limites do tier free relevantes ao dimensionamento: ordem de **1.500 requisições/dia** e
**10-15 RPM** na família Flash. Para uso familiar (dezenas de comprovantes/mês) isso é folgado por
duas ordens de grandeza; a cota só vira restrição real se a Fase 3 mandar PDFs de extrato inteiro
para a camada de IA. Esse é precisamente o cenário em que o fallback deixa de ser seguro contra
falhas e vira caminho quente — e o momento de reavaliar (§7).

---

## 5. Impactos

### 5.1 Proveniência: o que gravar, e por que a coluna de hoje não basta

`import_batches` já tem `extractor_used VARCHAR(100)` (ex.: `vision_ollama_qwen2.5vl`) e
`extractor_version`. Com um provider só isso era suficiente. Com dois, **três perguntas que passam
a importar ficam sem resposta**:

| Pergunta | Com o schema de hoje |
|---|---|
| Que fração das extrações caiu no fallback? | **Indecidível.** `vision_ollama_x` não distingue *"Ollama porque é o único"* de *"Ollama porque o Gemini estourou a cota"*. |
| Qual provider produz mais `requiresReview`? | Só via `LIKE 'vision_gemini_%'` — frágil: os extratores determinísticos gravam `csv_generic_v1`/`ofx_parser_v1`, sem prefixo comum. Não há `GROUP BY` honesto. |
| Qual a latência por provider? | **Não persistida** — só existe em log, e log não se agrega retroativamente. |

A terceira dói particularmente: a spec da Fase 1 elegeu **latência** como critério de saída ("no
homelab o custo em $ é zero; medimos tempo"), e hoje a única forma de compará-la entre providers é
ler log a olho.

**Decisão: migration V28** (aditiva, `import_batches`):

| Coluna | Tipo | Papel |
|---|---|---|
| `extractor_provider` | `VARCHAR(30)` | `gemini`, `ollama`, `csv`, `ofx` — fato de primeira classe para `GROUP BY` |
| `extractor_model` | `VARCHAR(100)` nullable | modelo efetivo; NULL para parser determinístico, que não tem modelo |
| `fallback_from` | `VARCHAR(30)` nullable | provider que falhou antes. **NULL = não houve fallback** |
| `fallback_reason` | `VARCHAR(200)` nullable | classificação (`quota`, `unavailable`, `auth`, `rejected_input`) + detalhe curto |
| `extraction_latency_ms` | `INTEGER` nullable | tempo da chamada que **venceu** |

Duas escolhas de modelagem que merecem defesa:

- **`fallback_from` codifica duas coisas numa coluna** (*houve fallback?* e *de quem?*) em vez de
  um booleano + um provider. Booleano separado seria estado redundante — dois campos que podem
  divergir e que ninguém lembra de manter em sincronia. NULL já é a resposta "não houve".
- **`extractor_used` permanece**, e não vira redundância a ser removida: ele é a string legível de
  proveniência (provider+modelo juntos, como o humano lê no log e no suporte). O par novo é a
  forma *consultável* do mesmo fato. Duplicação de fato normalmente é erro; aqui o custo de manter
  os dois em sincronia é uma linha no mesmo ponto de escrita, e o benefício é não reescrever o
  formato de uma coluna que já tem dado gravado.

**Backfill no V28:** batches legados recebem `extractor_provider` derivado do `extractor_used`
existente (`vision_ollama_%` → `ollama`, `csv_%` → `csv`, `ofx_%` → `ofx`). É derivação
determinística de dado que já temos — deixar NULL seria fingir ignorância sobre algo conhecido.
`extraction_latency_ms` e `fallback_*` ficam NULL nos legados: **honesto**, esses fatos realmente
não foram medidos.

**Dataset (regra inviolável de `dataset.md`):** coluna nova em tabela existente → os INSERTs do
seed precisam contemplá-la. Como V24/V27 já foram aplicados (migrations são imutáveis), a
atualização vai por **nova versão de seed V29**, que faz `UPDATE` nos batches seed populando as
colunas novas — precedente idêntico ao V18 corrigindo o `opening_balance` do V16.

### 5.1.1 Isso é exposto na API?

**Não nesta entrega.** Proveniência é dado operacional; a consulta é SQL. Expor
`extractorProvider` no `ImportBatchResponseDTO` mudaria `openapi.yaml` (e arrastaria
`api-sync.sh` + frontend) para um ganho hoje hipotético.

Vale registrar o caso que *poderia* justificar isso depois: mostrar "extraído por IA local" vs.
"extraído por IA gerenciada" na tela de revisão ajuda o usuário a calibrar quanta atenção dar aos
campos. É argumento de UX legítimo — e por isso mesmo pertence à metade B da Fase 2 (revisão em
lote), não a uma spec de infraestrutura de provider.

### 5.2 Contrato de API

`api-spec/openapi.yaml` **não muda**. Nenhum campo de request/response é criado, removido ou
renomeado; o frontend não sabe (nem deve saber) qual modelo leu a imagem.

**Impacto SemVer sugerido: MINOR** — capacidade nova, retrocompatível no contrato.

### 5.3 Risco: assimetria de prompt entre modelos

O prompt foi escrito e ajustado contra o `llama3.2-vision` (inclusive defesas explícitas contra
patologias daquele modelo: "nunca use números escritos nestas instruções", fallback de data
`dd/MM/yyyy`). Um modelo melhor pode reagir diferente ao mesmo texto — normalmente melhor, mas não
garantidamente.

**Mitigação nesta entrega:** validação manual contra comprovantes reais na Onda 4, comparando
Gemini e Ollama lado a lado no mesmo arquivo. **Mitigação estrutural:** issue #191 (dataset de
avaliação de 50-100 comprovantes + harness de precisão/latência) — que esta feature torna
consideravelmente mais valiosa, porque passa a haver dois providers para comparar objetivamente
em vez de um para aceitar por falta de alternativa. Fica registrado como dependência desejável,
não bloqueante.

### 5.4 Segurança

`GEMINI_API_KEY` é segredo: entra por env var, nunca no `application.properties` versionado
(mesmo padrão de `JWT_SECRET`). **Nunca logar a chave**, nem em trecho, nem em mensagem de erro —
e como o `GlobalExceptionHandler` só deixa passar a mensagem de `ExtractionException` redigida por
nós (#193), a mensagem de erro do provider não cruza a borda da API por construção. Confirmar isso
com teste é item da Onda 3.

Nota de privacidade que o dev deve decidir conscientemente: **a imagem do comprovante passa a sair
do homelab e ir para um serviço do Google.** Hoje o dado nunca deixa a rede local. Não é bloqueio
— é uma mudança de postura de dados que merece estar escrita em vez de acontecer por efeito
colateral de uma escolha técnica.

---

## 6. Como saber que funcionou

- Com `GEMINI_API_KEY` ausente: suíte verde e comportamento idêntico ao de hoje (`extractorUsed`
  segue `vision_ollama_*`).
- Com chave presente: comprovante real extraído com `extractorUsed=vision_gemini_*` e latência
  logada menor que a do Ollama no mesmo arquivo.
- Simulando 429 no cliente Gemini: batch conclui com `extractorUsed=vision_ollama_*` e WARN de
  fallback no log — o usuário não percebe diferença além do tempo.
- Simulando imagem de extrato (multi-transação): batch `FAILED` com a mensagem de #193 e
  **exatamente uma** chamada de provider — o fallback não dispara.
- A proveniência responde por SQL, sem `LIKE`:
  ```sql
  SELECT extractor_provider, count(*), avg(extraction_latency_ms),
         count(*) FILTER (WHERE fallback_from IS NOT NULL) AS via_fallback
  FROM import_batches WHERE tenant_id = :tenant GROUP BY 1;
  ```
  Essa query é o critério: se ela responde "quanto caiu no fallback e quão rápido cada provider
  é", a proveniência está modelada certo.

---

## 7. Diferido explicitamente

Não construir sem dor medida:

- **Contagem própria de cota / circuit breaker.** Hoje descobrimos que a cota acabou levando um
  429. Manter assim: contador local seria um segundo estado da verdade, que diverge do real e
  precisa de manutenção. Reavaliar se o custo de latência do 429-e-cai passar a incomodar.
- **Cache de extração por hash de imagem.** O dedup por `sha256` já barra o re-upload do mesmo
  arquivo antes de extrair (Fase 2), o que cobre a maior parte do desperdício.
- **Terceiro provider / seleção por custo.** A porta suporta N implementações por construção;
  adicionar um terceiro é adicionar um `@Component`. Não há motivo hoje.
