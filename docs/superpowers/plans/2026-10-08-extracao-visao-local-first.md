# Extração por visão — local-first: Plano de Execução

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recomendado) ou superpowers:executing-plans para orquestrar as tasks deste plano. As tasks
> usam checkbox (`- [ ]`) para tracking.

**Goal:** cortar a dependência de LLM externo (Gemini) na extração por imagem/PDF escaneado,
com evidência de acurácia — reenquadrando a ADR-007 e medindo os candidatos locais antes de
inverter o funil.

**Arquitetura:** o sub-funil de visão (`VisionExtractor`) resolve provider por `@Order`
(Gemini 10 → Ollama 20) e só cai para o próximo por indisponibilidade. A mudança-alvo é de
**política** (ordem/remoção do provider), mas é **gateada por medição**. O hardware é o homelab
`deathstar-server` (2× GTX 1080 Ti Pascal, Ollama 0.24.0 fora do k8s). Decisões completas:
spec `2026-10-08-extracao-visao-local-first-design.md` e ADR-007.

**Tech Stack:** Java 21 / Spring AI (Ollama + Google GenAI), Ollama 0.24.0, k3s (infra
`homelab-k8s`), Flyway (proveniência V28), shell para benchmark.

## Global Constraints

- **Nada editado em `develop`/`main`** exceto docs (spec/plano/ADR). Código só em worktree
  derivada de `develop`.
- **Baseline verde** antes de qualquer mudança de código (ver `fintech-core-validation-and-qa`).
- **Sem `Co-Authored-By`**; commits PT-BR imperativos.
- **Sem mudança de contrato** em `api-spec/openapi.yaml` no experimento → SemVer PATCH; se a
  política de provider mudar comportamento observável do import no futuro, reavaliar.
- **Privacidade:** nenhum documento de cliente é enviado ao Gemini no benchmark (spec §2 e).
- Mudanças no **host** (`deathstar-server`) afetam o OpenWebUI (mesmo Ollama) — coordenar.
- Critérios de aceite e tamanho do corpus **ratificados antes** de rodar; não afrouxar depois.

---

### Task 0: Docs (spec + plano + ADR-007 reescrita) — na `develop`

**Files:**
- Create: `docs/superpowers/specs/2026-10-08-extracao-visao-local-first-design.md`
- Create: `docs/superpowers/plans/2026-10-08-extracao-visao-local-first.md`
- Modify: `docs/adr/ADR-007-modelo-visao-ollama-homelab.md`

Reenquadra a ADR no propósito (custo/dependência), corrige fatos (V28 × V23, Textract,
hardware 2 GPUs/Pascal/disco) e registra que a escolha de modelo é decidida pelo experimento.

Commits: `docs(spec): ...` → `docs(plan): ...` → `docs(adr): reescreve ADR-007 ...`

- [x] Spec commitada
- [x] Plano commitado
- [x] ADR-007 commitada

---

### Task 1: Preparo do ambiente Ollama (host, fora do k8s)

**Files (host `deathstar-server`):** `/etc/systemd/system/ollama.service.d/override.conf`

1. Registrar o override atual (backup) antes de mudar.
2. **Liberar disco** em `/mnt/ollama-data` (93%, ~18 GB livres): listar modelos ociosos e
   **confirmar a lista com o dono** antes de remover.
3. Ajustar `CUDA_VISIBLE_DEVICES=0,1`; manter `OLLAMA_FLASH_ATTENTION=1`,
   `OLLAMA_KV_CACHE_TYPE=q4_0`, `OLLAMA_CONTEXT_LENGTH=12288`; avaliar `OLLAMA_NUM_PARALLEL`.
4. `ollama pull glm-ocr qwen3-vl:8b openbmb/minicpm-v4.5:8b`.
5. **Validar runner Pascal:** carregar cada modelo e exigir `ollama ps` em **GPU** (não 100%
   CPU); medir VRAM com `nvidia-smi`.
6. Smoke-test com 1 comprovante real usando o `VisionExtractor.PROMPT`.

Run: `ollama ps` (após carregar cada modelo) → `PROCESSOR` deve conter GPU.
Expected: todos os modelos carregam em GPU e cabem em VRAM.

- [x] Backup do override
- [x] Lista de remoção aprovada + disco liberado
- [x] `CUDA_VISIBLE_DEVICES=0,1` aplicado
- [x] Modelos puxados
- [x] Runner validado em GPU
- [x] Smoke-test sintético (comprovante real na Task 2)

**Achados empíricos (2026-10-08):**

- Override do systemd exige o cabeçalho `[Service]`; sem ele o systemd ignora as linhas
  `Environment=` e o Ollama sobe com defaults (`127.0.0.1:11434`, pasta de modelos default),
  derrubando o acesso do backend no k8s. Arquivo correto versionado fora do repo em
  `/home/sergio/ollama-host-backup/override.conf.new`.
- Removidos 10 modelos ociosos: disco `18 GB → 87 GB` livres (93% → 63%).
- Puxados: `glm-ocr:latest` (2,2 GB), `qwen3-vl:8b` (6,1 GB), `openbmb/minicpm-v4.5:8b` (6,1 GB).
- **Runner Pascal OK**: todos carregam com `PROCESSOR = 100% GPU`.
- **8B cabe em 1 GPU** com `OLLAMA_NUM_PARALLEL=1` (a leitura de 10,5 GB do `qwen2.5vl:3b` vinha
  do paralelismo default, não dos pesos). A 2ª GPU serve para rodar dois modelos simultâneos ou
  contexto/paralelismo maior, não para um 8B.
- Teste com comprovante sintético (valor/data/origem): `glm-ocr` ✅ 6,5 s / 3,8 GB; `qwen3-vl:8b`
  ✅ 26,2 s / 6,9 GB; `openbmb/minicpm-v4.5:8b` ✅ 12,6 s / 6,3 GB (carregou `CONTEXT 4096`);
  `llama3.2-vision` ❌ alucinou código Python (22,3 s / 8,9 GB); `qwen2.5vl:3b` resposta vazia.
- **Correção à spec:** a coluna "1×11 GB" da spec §3 (8B = "⚠️ apertado") é empiricamente **✅** com
  `NUM_PARALLEL=1`; corrigir na ADR quando a decisão for finalizada (Fase 3).

---

### Task 2: Corpus + ground truth

**Files:** `docs/superpowers/` (manifesto do corpus) + diretório local de dados (não versionado,
se contiver dados reais)

Montar 40 documentos PT-BR (mix Pix/recibo/cupom/fatura/foto/PDF escaneado) com ground truth
humano por campo (`valor` BigDecimal, `data` ISO, `direção`, `estabelecimento`, `tipo`).

- [ ] Corpus montado e rotulado
- [ ] Manifesto do corpus escrito (sem dados sensíveis versionados)

---

### Task 3: Harness de benchmark (worktree)

**Files:** a definir (script de scoring + execução do backend local Ollama-only)

Abordagem primária (fiel ao pipeline): backend local com `GEMINI_API_KEY` ausente →
Ollama-only; trocar `OLLAMA_MODEL` a cada run; coletar as extrações e comparar contra o ground
truth com normalização (BigDecimal / ISO / categórico).

- [ ] Worktree criada de `develop` atualizada; baseline verde
- [ ] Harness de scoring implementado
- [ ] 1 run de fumaça (baseline) validado

---

### Task 4: Executar benchmark + relatório

Rodar o roster (`llama3.2-vision` baseline + `glm-ocr` + `qwen3-vl:8b` + `minicpm-v4.5:8b`;
opcional `qwen2.5vl:7b`) contra o corpus, medindo por campo, latência p50/p95, VRAM, falhas.

- [ ] Runs completos
- [ ] Relatório comparativo escrito
- [ ] Veredito vs critérios de aceite (sem afrouxar)

---

### Task 5: Decisão e finalização da ADR

Com o veredito: manter / inverter `@Order` (Ollama primário) / remover Gemini / rota híbrida.
Finalizar a ADR-007 (ou abrir `ADR-008` para a política de provider).

- [ ] Decisão registrada na ADR com números
- [ ] Se inverter: `requires_review` por `extractorProvider == "ollama"` + teste; preservar piso
      no `PATCH`
- [ ] Suíte verde; merge em `develop` com aprovação

---

### Task 6: Follow-up — instrumentação prospectiva (issue separada)

Abrir issue para preservar a extração inicial × valor final aceito por campo (atribuído por V28),
com os cuidados da spec §5. **Não** implementar neste ciclo.

- [ ] Issue aberta
