# Roadmap — Extração Multi-Mídia e Conciliação de Transações

**Produto:** SaaS financeiro — gestão de finanças pessoais
**Objetivo final:** conciliação de extratos/faturas contra transações lançadas no sistema
**Status:** ideação / arquitetura
**Última revisão:** julho/2026

---

## 1. Contexto e decisões arquiteturais

Estas decisões foram tomadas durante a fase de ideação e sustentam todo o roadmap. Mudá-las depois tem custo alto.

### 1.1 Pipeline determinístico, não agente

O fluxo "mídia → transação" é um **pipeline determinístico com um único passo de IA generativa isolado** (a extração via modelo de visão/linguagem). Roteamento, validação, dedup, categorização e matching são código convencional.

Justificativa: custo previsível (uma chamada por imagem), testabilidade (regressão com dataset fixo), debug simples (chamada única com prompt fixo vs. cadeia de decisões autônomas). Comportamento agentic fica reservado como evolução futura para casos genuinamente ambíguos (documentos multi-página duvidosos, enriquecimento de estabelecimento via ferramentas externas).

### 1.2 IA é a cobertura universal; templates são otimização de custo

O modelo mental correto **não** é "templates cobrem os formatos, IA é o fallback para exceções" — é o inverso: **a IA generativa (visão/LLM) é a camada de cobertura universal** (format-agnostic por natureza, absorve qualquer banco/formato/versão, inclusive os que nasceram ontem), e **templates determinísticos são um "cache compilado"** que otimiza custo e confiabilidade para os formatos de alto volume (a cabeça da curva: poucas instituições concentram 80-90% do uso).

| | Template determinístico | Extração via IA |
|---|---|---|
| Custo por uso | ~zero | tokens por documento |
| Custo de manutenção | contínuo, quebra sem aviso | zero — absorve variação |
| Confiabilidade | altíssima (quando casa) | boa, mas probabilística |
| Cobertura | só o que foi mapeado | qualquer formato |

Funil de decisão (por heurística de código, nunca pelo modelo):

1. **Padrão universal** (OFX) → parser único, resolve N bancos
2. **Template conhecido casa** → determinístico, custo ~zero (só existe para alto volume)
3. **Parser genérico resolve** → heurística de colunas/estrutura para arquivos "bem comportados"
4. **IA generativa** → cobre todo o resto, transparente para o usuário

Consequência estrutural: **cobertura nunca é o problema — no pior caso, o documento custa mais tokens.** Mudanças imprevisíveis de formato degradam custo, nunca funcionalidade. Cobertura total via enumeração de templates (um por formato × instituição × versão) seria impossível de manter; esse nunca é o plano.

**Ciclo de vida do template** (Fases 3 e 6): documento desconhecido cai na IA → telemetria acumula volume e custo por formato → quando o volume justifica, a própria IA propõe o template a partir de exemplares → humano valida contra amostra → template promovido. Quando drift é detectado (taxa de "template casou" despenca), o formato cai de volta na IA sem incidente, até o template ser regenerado.

### 1.3 Schema normalizado como contrato central

Todos os extratores convergem para o mesmo schema, desacoplando extração das regras de negócio:

```json
{
  "batch_id": "uuid",
  "import_mode": "new_transactions | reconciliation",
  "source": {
    "type": "image | pdf_text | pdf_scanned | csv | ofx | audio",
    "extractor_used": "vision_v2 | pdf_template:itau_fatura | csv_template:nubank | ofx_parser",
    "extractor_version": "2026-06-01"
  },
  "transactions": [
    {
      "transaction_id": "uuid",
      "fields": {
        "amount":         { "value": 127.50,            "confidence": 0.98 },
        "transaction_date": { "value": "2026-06-28",    "confidence": 0.95 },
        "posting_date":   { "value": "2026-06-30",      "confidence": 0.90 },
        "description":    { "value": "PADARIA SAO JOSE", "confidence": 0.90 },
        "direction":      { "value": "debit",           "confidence": 0.99 },
        "payment_method": { "value": "pix",             "confidence": 0.85 }
      },
      "suggested_category": { "value": "alimentacao", "confidence": 0.70, "source": "heuristic" },
      "overall_confidence": 0.91,
      "requires_review": false,
      "duplicate_candidate_of": null
    }
  ]
}
```

Pontos-chave:

- **Confidence por campo** (não só por transação): a UI destaca apenas o campo problemático; thresholds de auto-aprovação podem variar por campo (valor é mais crítico que descrição).
- **`requires_review` é derivado por regra no código**, nunca decidido pelo modelo — o produto controla a régua sem retreinar nada.
- **Proveniência completa** (`extractor_used` + versão): toda transação sabe qual regra/modelo a gerou.
- **Datas distintas desde o dia 1**: `transaction_date` (compra) vs `posting_date` (lançamento/fechamento). Custa quase nada agora e evita migração dolorosa quando a conciliação de cartão de crédito chegar (Fase 5).

### 1.4 Registry de templates bancários

Templates versionados por instituição para CSV/PDF, separando detecção de extração:

```json
{
  "template_id": "nubank_extrato_csv_v1",
  "bank": "nubank",
  "input_type": "csv",
  "detection": { "header_signature": ["Data", "Valor", "Identificador", "Descrição"], "delimiter": "," },
  "field_mapping": {
    "date":   { "column": "Data", "format": "DD/MM/YYYY" },
    "amount": { "column": "Valor", "decimal_separator": "." },
    "direction": { "rule": "amount_sign" }
  }
}
```

- Cascata completa: template exato → parser genérico → **extração via IA transparente para o usuário** (mapeamento manual vira recurso opcional/raro, não último recurso obrigatório).
- Versionamento obrigatório: bancos mudam formato sem aviso; monitorar taxa de "nenhum template casou" por banco. Quebra de template não é incidente — o formato cai para a IA e vira variação temporária de custo.
- OFX é padrão único — um parser resolve vários bancos; incentivar na UX quando disponível.
- Templates são gerados sob demanda de volume (com assistência da própria IA), nunca escritos preventivamente para cobrir instituições sem uso.

### 1.5 Validações de sanidade como guarda-corpo comum

Independente da camada que extraiu (template ou IA), toda saída passa por validações determinísticas pós-extração: soma das transações bate com o total declarado no documento; datas dentro do período do extrato; valores em ranges plausíveis; schema íntegro. É o mesmo guarda-corpo para os dois modos de falha — template quebrado e alucinação de modelo. Princípio: **erro explícito > erro silencioso**, em qualquer camada.

### 1.6 Open Finance como trilho paralelo

Boa parte do problema de parsing existe porque extraímos dados de documentos feitos para humanos. O Open Finance Brasil (APIs padronizadas pelo BC, tipicamente acessadas via agregadores como Pluggy/Belvo/Klavi) elimina o parsing para instituições conectadas — importação vira sincronização automática e a conciliação ganha fonte de verdade que chega sozinha.

Não substitui o pipeline de mídia (prints, PDFs recebidos por e-mail, arquivos antigos e instituições fora do Open Finance continuam existindo), mas encolhe a cauda a cobrir via arquivo. É um **trilho paralelo ao roadmap, não uma fase dele**, com decisão própria de custo (mensalidade de agregador por usuário conectado vs. tokens de extração). Registrado aqui como decisão estratégica em aberto.

### 1.7 Conciliação como problema de matching

Conciliação **reusa 100% do pipeline de extração** e adiciona um motor de matching determinístico (score ponderado: diferença de valor + tolerância de data + similaridade de descrição) contra as transações já lançadas no período. Quatro categorias de saída:

| Categoria | Significado | Ação |
|---|---|---|
| Match exato | Valor + data batem | Auto-concilia |
| Match provável | Valor bate, data ±3-5 dias ou descrição similar | Sugere, usuário confirma |
| Órfão do extrato | Transação real sem par no sistema | Sugere criar lançamento (esquecimento) |
| Órfão do sistema | Lançamento sem par no extrato | Sinaliza possível erro/duplicata/não compensado |

Distinção importante: isso é **conciliação extrato × sistema** (auditoria), diferente do **dedup import × import** (evitar duplicata) — mas ambos compartilham o mesmo algoritmo de similaridade, o que ordena o roadmap.

---

## 2. Roadmap por fases

Princípio de ordenação: **construir de dentro pra fora** — cada fase é usável e gera valor sozinha, e reduz risco da seguinte. Conciliação vem por último porque não tem extrator próprio: é reuso de tudo que veio antes, e depende de base instalada de transações para ter contra o que comparar.

---

### Fase 0 — Fundação

**Objetivo:** contrato de dados correto antes de qualquer extrator.

**Entregas**
- Schema de normalização (confidence por campo, `batch_id`, proveniência, `import_mode`, datas distintas)
- `currency` no campo `amount` e contexto de locale nos extratores (não-bloqueio de expansão global — custa nada agora, evita migração depois; global de verdade só quando houver tração)
- Thresholds provisórios de `requires_review`
- Estrutura de banco: transações + batches + proveniência

**Critérios de saída (binários, fase de dias, não semanas)**
- [ ] Schema validado contra os casos futuros: multi-transação, conciliação, granularidade de datas do cartão
- [ ] Batch fake inserido e consultado ponta a ponta com dados mockados
- ⚠️ Sinal de alerta: discussão de schema virando semanas = perfeccionismo; o desenho atual já cobre o essencial

---

### Fase 1 — MVP de extração: comprovante único por imagem

**Objetivo:** provar o pipeline ponta a ponta com o caso mais simples e frequente.

**Entregas**
- Extrator de visão (print/foto de comprovante PIX/cartão — uma transação por imagem)
- Prompt de extração + validação de schema na saída
- Tela de revisão simples (confirmar/editar antes de lançar)
- Fallback manual quando a extração falha

**Critérios de saída**
- [ ] **Precisão ≥95% em valor e data** num dataset próprio de 50–100 imagens reais variadas (bancos diferentes, iluminação ruim) — valor errado é o pior erro possível; não negociável
- [ ] **Taxa de edição ≤10-15%** nos campos marcados como alta confiança (acima disso, thresholds descalibrados)
- [ ] Taxa de falha total conhecida, com fallback manual funcionando
- [ ] Latência p95 upload→preview aceitável (poucos segundos, com feedback visual)
- [ ] **Aprendizado:** custo real por extração (tokens/imagem) conhecido para precificação de planos

**Fora de escopo (conhecido), com recusa explícita:** imagem contendo **múltiplas transações** (print do extrato completo, não um comprovante único). O extrator é 1:1 por desenho (schema plano — mais fácil pro modelo de visão preencher certo). O caso é **detectado e recusado** (#193, entregue): o modelo sinaliza a lista de lançamentos, a extração falha e o batch `FAILED` carrega o motivo exibível ao usuário — em vez de escolher uma linha arbitrária e descartar o resto calado. Suporte real a multi-transação por imagem é escopo da Fase 3 (#194).

---

### Fase 2 — CSV/OFX e revisão em lote

**Objetivo:** cobrir formatos que trazem muitas transações de uma vez.

**Entregas**
- Parser de CSV genérico (sem registry ainda — colunas comuns por aproximação) — **entregue, metade A**
- Parser OFX (padrão único, vários bancos de uma vez) — **entregue, metade A**
- UX de revisão em lote: tabela, seleção múltipla, edição de conta/categoria em massa, descarte de linhas — **entregue, metade B** (#201)
- Dedup intra-batch (mesmo arquivo importado 2x não duplica) — **entregue, metade A** (via `external_id`/FITID ou trio data+valor+descrição; nenhuma linha descartada, só marcada)
- Validação de sanidade embrionária (schema íntegro, datas plausíveis) — **entregue, metade A**. "Totais consistentes quando o arquivo declara saldo/total" (ex.: `LEDGERBAL` do OFX) é escopo da **Fase 3** (CSV/OFX genéricos raramente declaram total; quem declara de forma relevante são faturas em PDF, daquela fase) — nunca foi pendência da metade B.

**Metade A entregue** (spec `docs/superpowers/specs/2026-07-28-extracao-fase2-csv-ofx-design.md`, plano `docs/superpowers/plans/2026-07-28-extracao-fase2-csv-ofx.md`, #196): `ExtractionRouter` generaliza a porta pra N transações por arquivo; `OfxExtractor` e `CsvExtractor` (parsers determinísticos); dedup por arquivo (409/`force`, `sha256` escopado por tenant) e intra-batch; guarda-corpo central de sanidade (`max-transactions`, data/valor implausível, zero linhas aproveitáveis); contrato (`force` no `openapi.yaml`) e frontend mínimo (aceita CSV/OFX, trata 409, badge de duplicata).

**Metade B entregue** (spec `docs/superpowers/specs/2026-07-30-extracao-fase2-revisao-em-lote-design.md`, #201, sub-issue do épico #175): tabela com paginação client-side, seleção múltipla (CDK `SelectionModel`), edição em massa de conta/categoria (local, sem endpoint novo) e endpoint novo `POST /staged/{stagedId}/discard` (o enum `DISCARDED` já existia desde a Fase 0 mas nenhum caminho de código gravava nele) — sem o descarte, uma única linha sem conta escolhida travava o commit do batch inteiro. O gate de confirmação foi relaxado de "100% das pendentes com conta" para "≥1 pendente com conta", permitindo lançar parte do batch e deixar o resto para uma revisão futura.

**Critérios de saída**
- [ ] Arquivos reais dos bancos dos usuários iniciais processados **sem erro silencioso** (erro explícito é ok; valor errado passando calado não é) — validado só com arquivos sintéticos até aqui; falta rodar com extratos reais de usuários
- [x] **Taxa de conclusão de revisão de batch** saudável: usuários revisam 30+ transações sem abandonar no meio — resolvido pela UX de revisão em lote (metade B): tabela paginada, seleção múltipla, edição em massa e descarte evitam que uma linha travada bloqueie o restante
- [x] Dedup intra-batch funcionando
- [ ] **Aprendizado:** distribuição real de bancos dos usuários — define quais templates construir na Fase 3 (ver nota na issue #196 — ainda não há volume real de uso pra medir isso)

---

### Fase 3 — PDF, registry de templates e a camada de cobertura universal

**Objetivo:** faturas de cartão — o formato mais heterogêneo e mais valioso para a conciliação futura — com a garantia estrutural de que **nenhum formato desconhecido bloqueia o usuário**.

**Entregas**
- Extrator de texto de PDF (heurística de linha, sem registry) — **entregue, fatia 1** (#205)
- Extração via visão para PDF escaneado — fatia futura
- **IA como camada universal**: qualquer PDF/CSV que não case com template nem parser genérico vai para extração via IA, transparente para o usuário (mapeamento manual vira opcional, não último recurso)
- **Camada de IA agora tem provider gerenciado primário** (plano "extração Gemini primário / Ollama fallback", entregue): a `VisionExtractor` tenta Gemini (Google AI Studio, tier free) antes do Ollama do homelab, caindo pro Ollama só por falha de disponibilidade (cota/5xx/timeout/auth). Relevante para o dimensionamento desta fase: se PDF/CSV desconhecidos passarem a rotear para extração via IA em volume, é a **cota gratuita do Gemini** que absorve a maior parte do tráfego primeiro — dimensionar/monitorar essa cota (não só a capacidade da GPU do homelab) vira parte do critério de saída da fase quando o volume via IA crescer
- Registry de templates para os 2–3 bancos principais (definidos pelos dados da Fase 2) — cabeça da curva apenas — **entregue, fatia 2**: Itaú (fatura PDF) e Nubank (extrato PDF). Nubank CSV não precisou de template — os headers reais já batem os sinônimos genéricos do `CsvExtractor` (Fase 2). CEF fica fora (só existe como print de imagem no caso avaliado — pertence a #194, não a registry de PDF/CSV)
- **Validações de sanidade pós-extração** (guarda-corpo comum a template e IA): soma × total declarado, datas × período do extrato, ranges plausíveis
- **Telemetria por formato**: volume, custo em tokens, taxa de casamento de template por banco — a base tanto do alerta de drift quanto da decisão de quais templates criar/promover
- **Extração multi-transação por imagem única** (print de extrato completo, não PDF): generalização do `TransactionExtractor`/`VisionExtractor` da Fase 1 (schema plano → lista), reaproveitando o mesmo caminho de visão que o PDF escaneado desta fase já implementa e as validações de sanidade acima (soma × total declarado). Spec própria antes de implementar — #194 (guarda-corpo de curto prazo em #193)

**Fatia 1 entregue** (spec `docs/superpowers/specs/2026-07-31-extracao-fase3-pdf-texto-design.md`, #205, sub-issue do épico #176): `PdfTextExtractor` (Apache PDFBox) reconhece PDF com camada de texto pelo magic number, extrai o texto via `PDFTextStripper` e reconhece transação por heurística de linha (data + valor na mesma linha) — sem registry de templates, sem validação soma × total, sem suporte a PDF escaneado (falha explícita, encaminhando para o formulário manual ou envio como imagem). Reaproveita 100% do pipeline genérico existente (`ExtractionRouter`, guard-rails de sanidade do `ImportService`, dedup por trio data+valor+descrição) — nenhuma mudança no núcleo.

**Fatia 2 entregue** (spec `docs/superpowers/specs/2026-08-05-extracao-fase3-registry-templates-design.md`):
`PdfBankTemplate` (interface + lista de beans ordenada, mesmo padrão do `VisionModelClient`)
tentado antes da heurística genérica dentro do `PdfTextExtractor`; nenhum template bate →
heurística genérica inalterada. Dois templates: Itaú fatura (delimitação de seção +
inferência de ano pela data de vencimento) e Nubank extrato PDF (state machine, direção
pela seção "Total de entradas"/"Total de saídas" corrente). Fallback para IA em PDF não
reconhecido por template nem heurística segue fora de escopo — depende de PDF→imagem,
mesma pendência de "PDF escaneado via visão".

**Critérios de saída**
- [ ] **Taxa de reconhecimento de template ≥90%** para os bancos cobertos
- [ ] **Zero bloqueio por formato desconhecido**: documento de banco nunca visto é extraído via IA sem intervenção
- [ ] Cascata exercitada em produção nas duas direções: escaneado → visão; template quebrado → IA (degrada custo, não funcionalidade)
- [ ] **Alerta de drift ativo**: queda na taxa de "template casou" de um banco dispara alerta — descobrir por reclamação de usuário é tarde demais
- [ ] Validações de sanidade pegando erros reais (de template e de IA) antes de chegarem ao usuário
- [ ] **Aprendizado:** custo mensal da camada de IA por formato conhecido — o dado que justifica (ou não) promover formatos a template
- [ ] Volume: importação regular de faturas/extratos completos (a Fase 4 precisa de histórico)

---

### Fase 4 — Categorização e dedup inteligente

**Objetivo:** maturar a lógica de similaridade antes de ela virar a base do matching de conciliação.

**Entregas**
- Categorização automática por histórico/heurística de estabelecimento
- Dedup import × import (mesma transação por fontes diferentes)
- Score de similaridade: pesos calibrados de valor + data + descrição

**Critérios de saída**
- [ ] **Falsos positivos de dedup <~2%** (duas compras iguais no mesmo dia tratadas como uma) — métrica-chave da transição, pois o matching da Fase 5 é o mesmo algoritmo
- [ ] Padrões que geram falsos positivos mapeados e entendidos
- [ ] Pesos do score calibrados com dados reais, não teóricos
- [ ] Categoria sugerida aceita na maioria dos casos (indicador de maturidade da similaridade de descrição)
- [ ] **Base instalada:** usuários com pelo menos um ciclo mensal completo de transações lançadas

---

### Fase 5 — Conciliação (objetivo final)

**Objetivo:** bater extrato/fatura fechada contra o que já está lançado.

**Entregas**
- Motor de matching com as 4 categorias (exato, provável, órfão do extrato, órfão do sistema)
- `import_mode: reconciliation` reaproveitando extratores das fases 1–3
- Tela de revisão de conciliação (distinta da revisão de import)
- Tratamento de datas do cartão de crédito (`transaction_date` vs `posting_date`, previsto desde a Fase 0)
- Janela de tempo do extrato restringindo o universo de comparação (reduz falsos positivos)

**Critérios de saída**
- [ ] **Taxa de reversão de match automático <1%** (usuário desfazendo match exato = critério de "exato" frouxo demais)
- [ ] **Cobertura de matching** diagnosticada: se a maioria cai em órfão, distinguir causa — usuários não lançam o suficiente (problema de produto) vs tolerância apertada (problema de algoritmo)
- [ ] Cartão de crédito conciliando com granularidade de data correta
- [ ] **Métrica de valor final: retenção do ciclo mensal** — quem concilia um mês volta no mês seguinte

---

### Fase 6 — Refinamentos (pós-conciliação)

- **Geração assistida de templates**: a telemetria da Fase 3 indica formatos de alto volume na camada de IA; a própria IA propõe o mapeamento estrutural a partir de exemplares; humano valida contra amostra; template promovido ao registry (e regenerado quando drift é detectado)
- Reconhecimento de recorrência (assinatura mensal → confiança maior de match automático)
- Áudio como canal de lançamento (mais próximo de assistente conversacional que de extração de documento — pode nascer como feature separada)
- Thresholds de confiança por plano/usuário
- Comportamento agentic para casos genuinamente ambíguos (multi-página, enriquecimento de estabelecimento)

---

### Trilho paralelo — Open Finance (decisão estratégica em aberto)

Independente das fases acima: conexão via agregador (Pluggy/Belvo/Klavi) para sincronização automática de instituições participantes. Elimina parsing para bancos conectados e fortalece a conciliação (fonte de verdade chega sozinha). Decisão de quando ativar depende de análise de custo (mensalidade por usuário conectado vs. tokens de extração) e de qual % da base usa instituições cobertas.

---

## 3. Padrões transversais

**Cada transição exige funcionalidade + qualidade + aprendizado.** A Fase 2 existe também para revelar quais templates construir na 3; a Fase 4 existe também para calibrar o matching da 5. Avançar sem os três é acumular risco silencioso.

**As métricas mais importantes são as de confiança do usuário** — taxa de edição de campos "confiáveis", taxa de reversão de match automático. Num app financeiro, um valor errado que passa despercebido custa mais caro em confiança do que dez extrações que falham explicitamente.

**Erro explícito > erro silencioso.** Em toda fase, falha visível com fallback é aceitável; dado incorreto passando calado nunca é.

**Mudanças de formato degradam custo, nunca funcionalidade.** Com a IA como camada universal, formato novo ou template quebrado significa "temporariamente mais caro", não "quebrado" — e a telemetria transforma essa variação de custo na fila de priorização de templates.
