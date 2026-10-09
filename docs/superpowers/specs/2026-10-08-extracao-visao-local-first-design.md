# Spec: Extração por visão — local-first e corte de dependência de LLM externo

**Data:** 2026-10-08
**Status:** proposto
**Issue:** a criar (referenciar no PR) — relacionada à ADR-007 (reescrita nesta mesma entrega)
**Épico relacionado:** #176 (Fase 3 — extração multi-mídia)
**Relacionado:** ADR-004 (homelab), ADR-007 (modelo de visão local), spec
`2026-07-29-extracao-gemini-primario-ollama-fallback-design.md` (arquitetura de funil vigente)
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto e escopo

O sub-funil de visão hoje é: `GeminiVisionClient @Order(10)` (primário,
`gemini-2.5-flash`) → `OllamaVisionClient @Order(20)` (fallback local, homelab). O fallback só
dispara por **indisponibilidade** (`VisionProviderUnavailableException`, classificada por
`VisionProviderErrorClassifier`: 429 / 5xx / timeout / 401 / 403 / 400), **nunca por qualidade**
de leitura.

O propósito declarado do dono é **cortar custo e dependência de LLM externo** mantendo a
performance de extração na importação via documentos (imagens, fotos, PDFs). Do levantamento
(ver ADR-007 e plano desta entrega) decorrem três fatos que reposicionam a decisão:

1. **Custo direto hoje é zero** (Gemini free tier + Ollama local). O ganho real a perseguir é
   **dependência/privacidade** — dado financeiro de cliente multi-tenant enviado ao Gemini free
   tier, que tipicamente usa o conteúdo para treino — e a redução da exposição a custo futuro ao
   estourar o free tier, não uma economia atual.
2. **Trocar o modelo do Ollama não corta a dependência**: enquanto o Gemini for o provider
   primário (`@Order(10)`), toda extração normal passa por ele. Só inverter a política de
   provider (ou desativar o Gemini) corta a dependência.
3. **Não há, hoje, evidência objetiva de acurácia** do modelo local. `overallConfidence` é
   auto-reportado pelo modelo; o `PATCH` do frontend envia **todos** os campos das linhas prontas
   (não apenas os corrigidos), então presença no patch não significa correção. Inverter sem medir
   é trocar qualidade desconhecida por dependência.

**Escopo afetado:** somente imagem/foto e PDF escaneado (roteados ao `VisionExtractor`). PDF de
texto (`PdfTextExtractor` + templates), OFX e CSV **não** passam por LLM e não têm ganho de
custo/privacidade a obter.

**Hardware alvo (verificado em 2026-10-08):** homelab `deathstar-server` — k3s single-node,
2× GTX 1080 Ti (Pascal cc 6.1, 11.264 MiB cada, ~22 GB), i7-7700K (4c/8t), 30 GiB RAM, Ollama
0.24.0 fora do k8s (systemd). Nenhuma GPU NVIDIA é usada pelo k8s; o Jellyfin transcodifica na
iGPU Intel via `/dev/dri`. A 2ª GPU está livre (hoje o Ollama usa só `CUDA_VISIBLE_DEVICES=1`).

**Escopo desta spec:**
- Definir a **política de provider local-first** e as metas mensuráveis que a autorizam.
- Especificar o **experimento de acurácia pareado** que precede qualquer inversão/remoção.
- Definir os requisitos de **instrumentação** (medição prospectiva por correção) e de
  **revisão forçada** para extração local.
- Corrigir a **ADR-007** no mesmo ciclo (reenquadramento + correções factuais).

**Fora de escopo (ver §7):** roteamento semântico por tipo de documento; pipeline de dois
estágios (glm-ocr transcreve → generalista interpreta) em produção; remover permanentemente a
integração Gemini; medição retroativa de batches antigos; enforcement de commit bloqueado por
`requires_review`.

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Alvo de política | **Ollama-first**: local primário, Gemini apenas como fallback de disponibilidade em deploys onde o processamento externo esteja explicitamente autorizado; sem autorização, Gemini desativado | Manter Gemini-first e só melhorar o fallback (ADR-007 original) — melhora qualidade local mas **não** corta dependência, que é o propósito |
| b | Sequência | **Medir antes de inverter.** Nenhuma inversão de `@Order` ou remoção do Gemini antes do experimento de acurácia (§4) | Inverter já, "porque o modelo melhorou" — troca dependência por risco de qualidade não medido, em app financeiro |
| c | Escolha do modelo local | Decidida **pelo experimento**, entre os que cabem no hardware (§3/§4); não fixar `minicpm-v4.5` a priori | Trocar para `minicpm-v4.5` por racional de fabricante — benchmarks auto-reportados, sem avaliação independente nem PT-BR |
| d | Gatilho de revisão local | Forçar `requires_review` por **`extractorProvider == "ollama"`** (proveniência efetiva), preservando o piso mesmo após `PATCH` | Reusar `fallbackFrom != null` — erra os dois casos: Ollama como primário tem `fallbackFrom == null`; após inverter, `fallbackFrom == "ollama"` significa que o Ollama falhou |
| e | Privacidade no benchmark | **Nunca** enviar documentos de cliente ao Gemini para medir. Usar dados consentidos/sintéticos com ground truth humano; se comparar contra Gemini, apenas amostra autorizada compatível com os termos | Usar comprovantes reais de clientes como baseline Gemini — transforma um teste de qualidade em egressão não autorizada |
| f | Escopo do ganho | Reconhecer explicitamente que **não há economia direta atual**; a meta é reduzir egressão/consumo de cota | Prometer "corte de custo" que a fatura atual não reflete |
| g | Isolamento do spike | O experimento é um **spike** (harness de scoring + backend local); não altera comportamento de produção. A inversão de provider é mudança de comportamento e vem depois, com sua própria evidência | Embutir a medição no fluxo de produção desde já — acopla um experimento a um caminho crítico |

**(a) Por que local-first e não "só melhorar o fallback".** O fallback por disponibilidade já é
um mecanismo de resiliência; ele não é um mecanismo de soberania de dados. Enquanto o Gemini for
primário, o caminho normal envia a imagem ao terceiro. O propósito declarado exige mover o
processamento para o controlado, e a arquitetura de funil já suporta isso apenas trocando a
ordem dos `@Order`.

**(b) Por que medir antes de inverter.** O `VisionExtractor` decide o provider por
disponibilidade, não por qualidade; um resultado local errado que retorna sem erro **não** aciona
o Gemini. Como o valor de um dígito trocado passa pelos guarda-corpos atuais (que só pegam valor
zero/data implausível) e vira transação após o commit, não existe atalho seguro sem medir.

**(c) Por que não fixar o modelo antes.** A pesquisa de modelos (ADR-007 §Alternativas) mostra
que os candidatos locais têm naturezas diferentes: transcritor puro (`glm-ocr`, forte evidência
independente em OmniDocBench, mas PT-BR não avaliado), generalista multilingual (`qwen3-vl:8b`) e
generalista de documento (`minicpm-v4.5`, só claims de fabricante). O corpus decide.

## 3. Hardware e modelos candidatos

Restrições duras do hardware (Pascal):
- Sem tensor cores; FP16 roda fração do FP32 (quantizar economiza VRAM, não acelera).
- Flash attention funciona, mas sem kernel tensor-core.
- CUDA 13 removeu Pascal; o Ollama contorna com runner `cuda_v12` embarcado — **validar que o
  runner ativo é GPU** (`ollama ps` não pode mostrar 100% CPU).
- Multi-GPU no Ollama é *layer-split* sequencial (sem tensor-parallel): a 2ª GPU dá
  **capacidade e paralelismo**, não menor latência de uma requisição.
- `/mnt/ollama-data` está a 93% (≈18 GB livres) — limpeza é pré-requisito dos pulls.

| Modelo (tag real) | Tamanho | 1×11 GB | 2×22 GB | Papel |
|---|---|---|---|---|
| `glm-ocr:latest` (0.9B) | 2,2 GB | ✅ | ✅ | transcritor puro (evidência independente forte; PT-BR a validar) |
| `qwen3-vl:8b` | 6,1 GB | ⚠️ apertado | ✅ | generalista multilingual + interpretação/JSON |
| `openbmb/minicpm-v4.5:8b` | 6,1 GB | ⚠️ apertado | ✅ | generalista de documento (claims de fabricante) |
| `llama3.2-vision:latest` (atual) | 7,8 GB | ⚠️ | ✅ | baseline do experimento |
| `qwen2.5vl:7b` (opcional) | 6,0 GB | ⚠️ | ✅ | reserva/substituto do generalista |
| `qwen3-vl:30b/32b`, `mistral-small3.1:24b` | 15–21 GB | ❌ | ❌ (peso + KV ≈25 GB com 12K) | inviáveis na Pascal |

## 4. Desenho do experimento de acurácia

**Método:** avaliação pareada em corpus controlado — é o único jeito de medir acerto objetivo em
vez de confiar em `overallConfidence` ou em benchmark de fabricante.

- **Corpus:** 40 documentos PT-BR (proposta a ratificar com o dono), misturando Pix, recibo,
  cupom, fatura de compra, foto de qualidade mediana e PDF escaneado. Cada documento recebe
  **ground truth humano por campo**: `valor` (`BigDecimal`), `data` (ISO), `direção`
  (débito/crédito), `estabelecimento`, e `tipo` (única/lista).
- **Roster:** `llama3.2-vision` (baseline atual do Ollama) + candidatos `glm-ocr`,
  `qwen3-vl:8b`, `openbmb/minicpm-v4.5:8b` (e `qwen2.5vl:7b` se necessário).
- **Controle:** mesmo pré-processamento, mesmo `VisionExtractor.PROMPT`, mesmo schema de saída
  para todos os modelos; execução **Ollama-only** (backend local com `GEMINI_API_KEY` ausente,
  que torna o bean Gemini condicional inativo), trocando `OLLAMA_MODEL` por run.
- **Métricas por campo:** acerto exato após normalização (valor `BigDecimal`, data ISO,
  direção categórica; estabelecimento com normalização textual); completude; taxa de extração
  falha/recusada; **latência p50/p95 ponta-a-ponta** (incluindo retries); VRAM de pico; cold start.
- **Critérios de aceite (proposta, a ratificar antes de rodar):**
  - `valor` e `data`: acerto exato ≥ **95%** (alinhado ao critério de saída da Fase 1) **e**
    não-inferioridade na mesma amostra contra o baseline atual do Ollama.
  - `direção`: ≥ 95%.
  - Latência p95 dentro de orçamento acordado (proposta: ≤ 20 s/página no hardware alvo).
  - Se comparado contra o Gemini, apenas em amostra explicitamente autorizada (§2 e).
- **Saída:** relatório comparativo em `docs/superpowers/` + veredito sobre inverter/remover/
  manter o Gemini. Números e critérios ficam registrados para a ADR.

**Regra:** nenhum critério pode ser afrouxado depois de ver os resultados. Se nenhum candidato
atingir os critérios, **não se promove** — avalia-se outra classe/modelo ou rota híbrida
comprovada, e a dependência do Gemini permanece.

## 5. Instrumentação prospectiva de acurácia (follow-up)

O experimento mede num corpus controlado uma vez. Para acompanhar acurácia em produção, é
necessário preservar o resultado **extraído** antes que o `PATCH` o sobrescreva e compará-lo ao
valor **final aceito** no commit, atribuído pela proveniência V28 (`extractor_provider`,
`extractor_model`, `extractor_version`). Isso é uma feature própria (issue separada), com dois
cuidados:
- O `PATCH` do frontend envia todos os campos preenchidos das linhas prontas, mesmo os não
  alterados — então "veio no PATCH" **não** é correção; é preciso comparar extraído × final.
- O commit não prova que cada campo foi conferido: erros não corrigidos ficam invisíveis (falsos
  negativos). Uma amostra auditada por humano estima essa taxa.

## 6. Riscos e ressalvas

| Risco | Mitigação |
|---|---|
| Local-only vira **SPOF** de disponibilidade da visão (homelab/k3s/energia) | Gemini como fallback de disponibilidade em deploys autorizados; importação manual como último recurso |
| Pascal/CUDA 13: runner errado → inferência em CPU (lenta) | Validar `ollama ps` em GPU no preparo do ambiente (plano, Task 1) |
| RAM/VRAM disputada com OpenWebUI (mesmo Ollama) e pico do CI (até 8 GiB) | Medir sob carga real; considerar `OLLAMA_NUM_PARALLEL` e retenção de modelo |
| Disco quase cheio (93%) | Limpar modelos ociosos antes dos pulls (plano, Task 1) |
| Qualidade PT-BR desconhecida (glm-ocr validado só EN/ZH) | Corpus pareado decide; sem atalho de configuração |
| Egressão de dado sensível no benchmark | §2 e — dados consentidos/sintéticos, sem cliente no Gemini |

## 7. Fora de escopo

- Roteamento semântico por tipo de documento em produção (falta sinal pré-extração confiável).
- Pipeline de dois estágios glm-ocr→generalista como arquitetura de produção.
- Remoção definitiva da integração Gemini (decisão pós-experimento).
- Medição retroativa de batches antigos (documentos originais não são preservados — só
  hash/nome).
- Enforcement de `requires_review` bloqueando commit (gap pré-existente do sistema; a flag hoje é
  sinal de UI).

## 8. Critério de conclusão

- [ ] Spec, plano e ADR-007 corrigida commitados na `develop`.
- [ ] Ambiente Ollama validado em GPU (runner Pascal), com disco liberado.
- [ ] Corpus com ground truth e critérios de aceite **ratificados antes** da execução.
- [ ] Relatório comparativo com números por campo, latência p95 e VRAM.
- [ ] Veredito registrado na ADR (inverter / remover / manter), com metas mensuráveis.
- [ ] Se invertido: `requires_review` por `extractorProvider == "ollama"` + teste.
- [ ] Issue de follow-up da instrumentação prospectiva aberta.
