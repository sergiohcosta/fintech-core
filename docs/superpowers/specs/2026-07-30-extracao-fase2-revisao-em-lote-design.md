# Spec: Extração — Fase 2 (metade B): UX de revisão em lote

**Data:** 2026-07-30
**Status:** entregue
**Fonte do produto:** `docs/roadmap-extracao-e-conciliacao.md` — Fase 2 ("CSV/OFX e revisão em lote")
**Spec anterior:** `docs/superpowers/specs/2026-07-28-extracao-fase2-csv-ofx-design.md` (metade A — backend, entregue, #196)
**Issue:** #201 (sub-issue do épico **#175** — Fase 2, milestone *Fase 2*)
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md

## 1. Contexto e escopo

A metade A entregou os parsers (OFX, CSV) e generalizou o pipeline para N transações por
batch. A tela de revisão, porém, não mudou desde a Fase 1: um `mat-card` expandido por
staged, com todos os campos abertos, sem seleção, sem paginação, sem ação em massa. Ela foi
desenhada para **uma** transação (o caso da Fase 1) e hoje é usada, sem ajuste, para batches
de dezenas de linhas — exatamente o gap que o roadmap já registrava como pendente ("UX de
revisão em lote... a tela de revisão continua item a item").

Rodando o fluxo mentalmente com o seed V27 (3 staged) e um CSV real de ~40 linhas, dois
problemas concretos aparecem, além da fricção óbvia de rolar 40 cards:

- **`canConfirm` exige que TODAS as staged `PENDING` tenham conta escolhida** (`import-utils.ts`,
  `allPendingHaveAccount`) antes de habilitar "Confirmar e lançar". Não existe hoje nenhuma
  forma de **descartar** uma linha (ex.: a candidata a duplicata, ou uma linha de rodapé que
  sobrou marcada) — o enum `StagedTransactionStatus.DISCARDED` já existe no domínio (Fase 0),
  mas nenhum endpoint transiciona para ele. Resultado prático: um batch com uma única linha
  indesejada trava o commit do batch inteiro.
- **Nenhuma edição em massa.** Definir a mesma conta em 40 linhas (o caso comum — um extrato
  inteiro de uma conta corrente) exige 40 cliques no `mat-select` de conta, um por card.

Esta spec cobre a metade B: tabela com seleção múltipla, edição em massa de conta/categoria,
paginação e a ação de descartar (individual e em lote). **Não** reabre parser, dedup nem
validação de sanidade (metade A, já entregue).

## 2. Decisões arquiteturais

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Layout da revisão | `mat-table` compacta (1 linha por staged) + expansão sob demanda para edição fina | Manter `mat-card` por staged |
| b | Paginação | **Client-side** (`mat-paginator` sobre o array já carregado) | Paginação server-side em `GET /staged` |
| c | Seleção múltipla | `SelectionModel` do CDK (`@angular/cdk/collections`), já disponível via Angular Material | Checkboxes soltas com `Set<string>` manual |
| d | Edição em massa | Aplica valor a `accountId`/`categoryId` das linhas selecionadas **em memória** (mesmo signal `rows`, sem chamada de API) | PATCH em lote no backend |
| e | Descartar linha | Endpoint novo `POST /api/imports/{id}/staged/{stagedId}/discard` (PENDING→DISCARDED); frontend chama em lote via `forkJoin` sobre a seleção | Sobrecarregar o `PATCH` existente com um campo `status` |
| f | Gate de confirmação | `canConfirm` passa a exigir "≥1 staged PENDING com conta preenchida" (não mais todas) | Manter exigência de 100% — é o que causa o travamento hoje |

**(a) Tabela, com expansão para o caso que precisa de edição fina.**
A maioria das linhas de um extrato real chega correta (é dado de arquivo, não alucinação de
IA — decisão (d) da metade A: confiança `1.0` no campo lido diretamente). O que o usuário
precisa fazer em volume é **escolher conta e, opcionalmente, categoria** — os dois únicos
campos que o extrator nunca preenche (não existem no arquivo de origem). Uma tabela com
colunas fixas (flags, valor, data, descrição, tipo, conta, categoria) resolve isso em uma
linha por transação, sem rolagem excessiva. Para o caso raro de precisar corrigir valor/data/
descrição (baixa confiança, linha ruim marcada), a linha expande e reaproveita exatamente os
`mat-form-field` que já existem no card atual — nenhum editor novo é escrito, só reorganizado.

**(b) Paginação client-side, não server-side.**
A metade A (§5.2) registrou isso como limite em aberto, mas o teto já existente
(`import.file.max-transactions`, default 500) balizou a decisão: 500 objetos JSON num
`GET /staged` já é uma resposta pequena (dezenas de KB), e paginar no servidor introduz
estado cruzado real — página atual × seleção × edição em massa × descarte precisam
permanecer coerentes entre páginas, o que soma complexidade genuína (parâmetros de página no
componente, invalidação de seleção ao trocar de página, contagem total separada). Server-side
só se justifica se o teto de 500 subir stat by muito (não há sinal disso) ou se a Fase 4
introduzir importação por conector com milhares de linhas — nesse caso a decisão é revisitada
com dado real, não hoje por especulação.

**(c) `SelectionModel` do CDK.**
Já é uma dependência transitiva do Angular Material (nenhum pacote novo). Dá de graça
`toggle`, `select`, `clear`, `selected.length`, `isSelected` — exatamente a superfície que o
toolbar de ações em massa precisa, sem reimplementar controle de seleção com um `Set` manual
espalhado pelo componente.

**(d) Edição em massa é local, não uma chamada de API nova.**
O fluxo de commit já persiste o estado final via `patchImportStaged` linha a linha (código
existente, `confirm()` em `import.ts`) antes do commit propriamente dito. Aplicar
"conta = X" às N linhas selecionadas é, hoje, uma atualização de N objetos no mesmo array de
signals que `updateField` já faz um de cada vez — só precisa iterar sobre os `stagedId`
selecionados em vez de um só. Criar um endpoint de PATCH em lote para algo que já é resolvido
no cliente, sem round-trip, seria complexidade sem benefício: o backend já recebe as edições
no momento certo (confirm), uma por staged, e isso não muda.

**(e) Descartar é um endpoint novo, não um PATCH de status.**
`patchImportStaged` (`StagedPatchDTO`) é, por contrato e por nome, "correção de campos antes
de lançar" — schema com `fields` + `suggestedCategoryCode`, semântica de edição. Transição de
estado (`PENDING → DISCARDED`) é uma decisão do usuário sobre o **destino** da linha, não uma
correção de valor, e o projeto já tem o padrão para isso: verbos de transição como
sub-recurso (`POST /invoices/{id}/close`, `POST /invoices/{id}/pay`,
`POST /recurrence-rules/{id}/occurrences/{date}/skip`). Path:
`POST /api/imports/{id}/staged/{stagedId}/discard`, sem corpo, 200 com a staged atualizada.
Só transiciona staged `PENDING` (outra transição → 400, mesmo padrão de `patchImportStaged`
e `commit`, que já rejeitam staged não-`PENDING`).

Descarte em lote (várias linhas selecionadas) reusa o **mesmo** endpoint individual via
`forkJoin` no frontend — igual ao padrão já usado em `confirm()` para os PATCHes de edição.
Não há necessidade de um endpoint de lote dedicado: o volume (dezenas, não milhares) e a
simplicidade de reusar uma rota já testada pesam mais que economizar N chamadas HTTP.

**(f) Relaxar o gate de confirmação.**
Hoje `allPendingHaveAccount` bloqueia o commit inteiro por causa de UMA linha sem conta —
correto quando "sem conta" era a única forma de dizer "ainda não decidi", mas com o
`discard` disponível, "ainda não decidi" (deixa para depois, conta ainda não escolhida) e
"não quero esta linha" (descarta) passam a ser ações distintas. O gate correto deixa de ser
"100% das pendentes têm conta" e passa a ser "pelo menos uma pendente tem conta" — o
`buildCommitRequest` já filtra por `accountId` presente (nenhuma mudança ali), então linhas
sem conta simplesmente ficam para uma rodada futura de revisão do mesmo batch. Isso também
resolve, de fato, o critério de saída do épico #175 ("usuários revisam 30+ transações sem
abandonar" — ninguém deveria precisar decidir as 40 de uma vez para lançar as 35 já prontas).

## 3. Invariante inviolável — isolamento de tenant

Nenhuma mudança no modelo de acesso. O endpoint novo (`discard`) segue exatamente o padrão
de `patchImportStaged`/`commitImport`: busca a staged pelo par `(batchId, stagedId)` já
filtrado por `user.getTenant()` no `ImportService`; staged de outro tenant → 404 (não
confirma existência). Teste obrigatório: descartar staged de outro tenant → 404.

## 4. Modelo de dados

Nenhuma migration. `StagedTransactionStatus.DISCARDED` já existe (V23) e a constraint
`status IN (PENDING, CONFIRMED, DISCARDED)` já permite o valor — só faltava o caminho de
código que grava.

## 5. Contrato de API

Novo endpoint, aditivo (nenhum existente muda de forma):

```yaml
/api/imports/{id}/staged/{stagedId}/discard:
  post:
    operationId: discardImportStaged
    summary: Descarta uma transação em staging (não será lançada)
    responses:
      '200': { StagedTransactionResponseDTO }   # status=DISCARDED
      '400': { descrição: staged não está PENDING }
      '404': { descrição: batch/staged não encontrado ou de outro tenant }
```

Spec-first, sem exceção: editar `api-spec/openapi.yaml` → `./scripts/api-sync.sh`.

## 6. Fluxo

### 6.1 Backend

```
POST /api/imports/{id}/staged/{stagedId}/discard
  ImportService.discardStaged(user, batchId, stagedId)
    → busca staged (tenant-scoped), 404 se ausente
    → staged.status != PENDING → 400 (BusinessException)
    → staged.status = DISCARDED; save
    → se não sobra PENDING no batch, o MESMO gate de commit (§4 da metade A,
      "batch vira COMMITTED quando nenhuma staged está mais PENDING") já cobre
      o caso "usuário descartou tudo que faltava"? NÃO — esse gate roda dentro do
      commit(), que só é chamado com a lista de items lançados. Descartar a ÚLTIMA
      pendente sem nunca chamar commit deixaria o batch em EXTRACTED para sempre.
      Decisão: discardStaged também verifica "sobra PENDING?" e marca o batch
      COMMITTED se não sobrar nenhuma (mesma condição, chamada de dois pontos).
```

**Ponto a revisar com atenção durante a execução:** extrair a checagem "sobra PENDING no
batch?" para um método privado único do `ImportService`, chamado tanto por `commit()` quanto
por `discardStaged()` — duplicar a condição nos dois lugares é o tipo de duplicação que
diverge silenciosamente numa mudança futura.

### 6.2 Frontend

```
Tabela (mat-table + mat-paginator, client-side sobre rows())
  ├─ coluna de seleção (mat-checkbox, cabeçalho = selecionar todas da página)
  ├─ colunas: flags (revisão/duplicata) · valor · data · descrição · tipo · conta · categoria
  ├─ linha expansível → reaproveita os mat-form-field do card atual (valor/data/descrição/tipo)
  └─ toolbar de ações em massa (visível quando selected.length > 0):
       ├─ "Definir conta" (mat-select) → aplica a todas as selecionadas (local, sem API)
       ├─ "Definir categoria" (mat-select) → idem
       └─ "Descartar selecionadas" → forkJoin de discardImportStaged por stagedId selecionada
                                       → remove da tabela ao sucesso (ou marca DISCARDED e
                                         filtra da view — mesma decisão de "rows visíveis
                                         excluem DISCARDED")

Confirmar e lançar → inalterado no destino (commit), gate relaxado (§2f)
```

`rows()` passa a excluir (ou exibir com estilo "descartada", à decisão de execução — mais
simples: excluir da tabela e manter só em memória para desfazer não é necessário, já que
descartar é ação do backend, não reversível nesta fatia) staged com `status=DISCARDED`.

## 7. Testes

| Camada | Cobertura |
|---|---|
| `ImportService` (unit) | discard de staged PENDING → DISCARDED; discard de staged já CONFIRMED/DISCARDED → 400; discard da última PENDING → batch vira COMMITTED; discard de staged de outro tenant → 404 |
| Controller (MockMvc) | 200 com corpo atualizado; 400; 404 |
| `import-utils.ts` (puro) | `allPendingHaveAccount` → renomear/ajustar para "≥1 com conta" (`anyPendingHasAccount` ou equivalente) + teste do caso atual (0 pendentes com conta → false; mistura → true); função pura de aplicar valor de campo a um conjunto de `stagedId`s (`applyBulkField(rows, selectedIds, field, value)`) — testável sem `TestBed` |
| Componente (`ng test`, não `npx vitest` cru) | seleção múltipla marca/desmarca linhas certas; toolbar aparece só com seleção não-vazia; descartar remove a linha e desmarca da seleção; paginação não perde seleção entre trocas de página (ou, se decidido limpar seleção ao trocar de página, testar esse comportamento explicitamente) |

## 8. Dataset de testes

Feature de frontend + um endpoint de transição de estado sem novo schema — não exige seed
novo (regra do dataset.md: "feature puramente de frontend / refatoração sem schema → nenhuma
atualização necessária" cobre a maior parte; o endpoint novo não introduz tabela/coluna).
`docs/http/seed-dataset.http` ganha 1 request de exemplo para `discard` sobre o seed V27
(a staged com `duplicate_candidate_of`, candidata natural a descarte).

## 9. Critérios de saída

Fecha o item pendente do épico #175:
- [ ] Batch de 30+ linhas (seed real ou fixture) revisado e commitado **sem precisar decidir
      as 100%** — parte fica para depois, testado explicitamente.
- [ ] Descartar uma linha (individual e em lote) funciona e não bloqueia o commit das demais.
- [ ] Definir conta em massa aplicada a 10+ linhas de uma vez, sem chamada de API por linha.
- [ ] Paginação client-side não quebra seleção nem edição das linhas fora da página atual
      (ou o comportamento de limpar seleção ao paginar está documentado e testado).
- [ ] Zero regressão no fluxo de imagem única (Fase 1) — segue funcionando com 1 linha só,
      tabela de 1 linha sem paginação visível.

## 10. Fora de escopo

- Paginação **server-side** de `GET /staged` — revisitar só com dado real de volume (§2b).
- PATCH em lote no backend para edição de campos — resolvido no cliente (§2d).
- "Soma × total declarado" (LEDGERBAL) — permanece Fase 3, conforme a metade A já decidiu
  (§11 daquela spec); o roadmap.md será corrigido nesta entrega para não listar isso como
  pendência da metade B (inconsistência entre o roadmap e a decisão já tomada na metade A).
- Desfazer um descarte (reverter DISCARDED→PENDING) — não pedido, e se vier a ser necessário
  é um segundo endpoint simétrico, não parte desta fatia.
- Categorização sugerida automática em massa — Fase 4 (dedup/categorização inteligente).

## 11. Riscos

| Risco | Mitigação |
|---|---|
| `SelectionModel` + `mat-table` com `@if`/expansão custam mais complexidade de template do que o `mat-card` atual | Expansão reaproveita o markup existente 1:1 (copiar, não reescrever); se o resultado ficar confuso, cair para um dialog de edição por linha em vez de expansão inline — decisão de execução, não bloqueia o design |
| Relaxar o gate de confirmação permite confirmar "quase nada" (1 de 40) sem aviso | UI mostra contagem explícita ("35 de 40 serão lançadas agora") antes de confirmar — mesma transparência que já existe para `duplicateCandidateOf` |
| Duplicar a checagem "sobra PENDING?" entre `commit()` e `discardStaged()` diverge no futuro | Extrair para método privado único (§6.1, nota de execução) |

## 12. Impacto SemVer

**MINOR** — `api-spec/openapi.yaml` ganha um endpoint novo (`discard`), aditivo. Nenhum
campo ou endpoint existente muda de forma.

## 13. Ordem de execução sugerida

1. Backend: `discardImportStaged` (service + controller + openapi + `api-sync.sh`) com os
   testes de unit/MockMvc/tenant do §7.
2. Frontend: extrair a checagem de gate (`allPendingHaveAccount` → variante "≥1") e a função
   pura `applyBulkField` em `import-utils.ts`, com testes Vitest, **antes** de tocar o
   componente.
3. Frontend: converter o card por staged em `mat-table` com expansão, mantendo o mesmo
   `ReviewRow`/signals — sem mudar o modelo de dados do componente, só a apresentação.
4. Frontend: `SelectionModel` + toolbar de ações em massa (conta, categoria, descartar).
5. Frontend: `mat-paginator` client-side sobre a tabela.
6. Corrigir `docs/roadmap-extracao-e-conciliacao.md` (remover a menção a "totais declarados"
   como pendência da metade B — já é Fase 3 pela decisão da metade A) e marcar a metade B
   como entregue nas entregas da Fase 2.
7. Validar critérios de saída (§9) com o seed V27 + um CSV sintético de 30+ linhas.
