---
name: fintech-core-research-frontier
description: >-
  Problemas abertos de fronteira do fintech-core — o que fazer a seguir, roadmap, próxima
  feature grande, pesquisa, experimento, spike, PoC. Cobre RLS no Postgres (#116),
  effective_date + paginação server-side (#85), sub-projetos do motor de recorrência
  (pausa/retomada, capping 31→28, simulador "E se...?"), JWT httpOnly cookie (#91),
  Patrimônio Total (#90), ADRs dormentes de Stripe e Fly.io, e a disciplina de experimento
  (hipótese com números previstos → worktree → comparação → adoção ou aposentadoria). Use
  quando a pergunta for "o que vale atacar agora?", "como transformo essa ideia em
  experimento?", "qual o status real de X?" ou envolver RLS, paginação, Stripe, billing,
  infra, fronteira. Para EXECUTAR a correção de um bug do backlog (incluindo o #85), use
  fintech-core-bug-backlog-campaign.
---

# Fronteira de pesquisa do fintech-core

Lista **curta e honesta** dos problemas abertos onde este projeto pode genuinamente avançar —
ou onde o dono (dev único, projeto de aprendizado) constrói habilidade de fronteira. Tudo aqui
é **aberto ou candidato**: nada desta página está implementado, e cada item declara seu status
real verificado contra o repo em 2026-07-04.

Cada item traz: (a) por que a abordagem atual é insuficiente, (b) o ativo específico deste
projeto, (c) os 3 primeiros passos concretos NESTE repo, (d) o marco falsificável — "você tem
um resultado quando…", sempre mensurável, nunca a olho.

## Quando NÃO usar

| Situação | Skill certa |
|---|---|
| Bug conhecido/reproduzível (backlog #135–#152, correção monetária, race, etc.) | `fintech-core-bug-backlog-campaign` |
| Processo de mudança (spec, plano, worktree, migration, commit, PR) | `fintech-core-change-control` |
| Teoria de domínio (saldo, fatura, RRULE, Modelo A) | `fintech-domain-reference` |
| Provar um invariante existente (isolamento de tenant, centavos, race) | `fintech-core-proof-and-analysis-toolkit` |
| Erro/exception agora na tela | `fintech-core-debugging-playbook` |

Toda mudança que nascer daqui **roteia pelo change-control** — esta skill decide *o que* vale
atacar e *como saber se deu certo*; ela não autoriza pular spec, plano e aprovação do dev.

---

## 1. RLS no Postgres como defesa em profundidade (issue #116 — aberta)

**(a) Por que a abordagem atual é insuficiente.** O isolamento multi-tenant depende 100% de
filtros na camada de aplicação (`findByIdAndTenant`, `WHERE tenant_id = :tenantId` em cada
query). O padrão é seguido com consistência, mas **um único método que esqueça o filtro**
(precedente real: #108) fura o isolamento — o bug mais grave possível neste projeto. Não há
nenhuma política RLS em `backend/src/main/resources/db/migration/` (verificado 2026-07-04:
`grep -rni "POLICY" db/` vazio). A camada de aplicação é a única linha de defesa.

**(b) Ativo deste projeto.** Disciplina de tenant já consistente (invariante enunciado em
`fintech-core-architecture-contract`), `SecurityFilter` que já resolve o tenant por requisição
(`backend/src/main/java/com/fintech/api/config/SecurityFilter.java:55` põe `tenantId` no MDC),
testes de integração `@SpringBootTest` contra Postgres real e o dataset Família Costa com
UUIDs fixos — o cenário de dois tenants para o teste discriminante é barato de montar.

**(c) Três primeiros passos.**
1. Escrever `docs/adr/ADR-004-rls-postgres.md` (adotar ou não — critério explícito da própria
   issue #116) apoiado no rascunho da issue: política
   `USING (tenant_id = current_setting('app.tenant_id')::uuid)` + `SET LOCAL` por transação.
2. PoC em worktree isolada: migration nova (próxima versão livre após V21) habilitando RLS
   **em uma tabela só** (`transactions`), com `ENABLE ROW LEVEL SECURITY` **e**
   `FORCE ROW LEVEL SECURITY` (sem `FORCE`, o owner da tabela — que é o usuário da app no
   docker-compose local — ignora as políticas). Mecanismo de `SET LOCAL` por requisição em
   aberto na issue: interceptor JPA vs. hook no `SecurityFilter` — decidir no ADR.
3. Teste de integração discriminante: query nativa **sem** filtro de tenant na aplicação, com
   `app.tenant_id` setado para o tenant A, contra base com dois tenants populados.

**(d) Você tem um resultado quando…** o teste do passo 3 retorna **exatamente 0 linhas** do
tenant B (contagem assertada, não inspeção visual), e o mesmo teste com RLS desabilitado
retorna N > 0 — provando que a política, e não o filtro da aplicação, bloqueou o vazamento.

## 2. `effective_date` + paginação server-side (issue #85 — aberta; ADR-001 item 3)

**(a) Por que a abordagem atual é insuficiente.** `findAllByTenantWithFilters`
(`backend/src/main/java/com/fintech/api/repository/TransactionRepository.java:68`) traz todas
as transações do período para a memória e a ordenação por `effectiveSortDate` acontece em Java
(`backend/src/main/java/com/fintech/api/service/TransactionService.java:91,106,116` —
verificado 2026-07-04). A coluna que representa essa data **não existe no schema**, então
`ORDER BY` + `LIMIT/OFFSET` no banco é impossível. Isso trava exportação/relatórios anuais
(carregam **tudo** do tenant), a futura tela de Patrimônio e qualquer paginação real.

**(b) Ativo deste projeto.** A regra de data efetiva já está formalizada e testada (fonte:
`fintech-domain-reference`), a issue #85 já traz o SQL de backfill rascunhado, e o export CSV
recém-entregue (commits de 2026-07 na `develop`) é o primeiro consumidor real que vai sentir a
diferença — dá um caso de medição imediato.

**(c) Três primeiros passos.**
1. Migration nova (próxima versão livre após V21): `ADD COLUMN effective_date DATE` + backfill
   segundo a regra de data efetiva (ver `fintech-domain-reference`; SQL na issue #85) + índice
   `(tenant_id, effective_date DESC)` + `SET NOT NULL`. Atualizar
   `backend/src/test/resources/sql/seed_base.sql` (regra do dataset — ver dataset.md; os seeds
   V13/V16 rodam antes da migration e são cobertos pelo backfill).
2. Escrita dupla: `TransactionService.create()/update()` e `InvoiceService.pay()` preenchem a
   coluna; manter `effectiveSortDateDto` como sombra temporária.
3. Teste de paridade: sobre o dataset seed, a lista ordenada por `ORDER BY effective_date DESC`
   no banco deve ser **elemento a elemento idêntica** à lista produzida pela ordenação em
   memória atual. Só depois da paridade verde, trocar a query para `ORDER BY` + `Pageable` e
   remover a ordenação em Java.

**(d) Você tem um resultado quando…** o teste de paridade passa (listas idênticas, assertadas)
**e** a listagem paginada emite SQL com `LIMIT` (visível no log do Hibernate no teste) em vez
de carregar o conjunto inteiro — os dois verificáveis por asserção, não por impressão.

## 3. Sub-projetos do motor de recorrência (#3 e #4 da spec do núcleo — não iniciados)

**(a) Por que a abordagem atual é insuficiente.** O núcleo entregue (regra vs. fato, projeção
on-the-fly, confirmar/pular) cobre só `FREQ=MONTHLY|YEARLY` com semântica RFC 5545 estrita. A
spec `docs/superpowers/specs/2026-06-25-motor-de-recorrencia-nucleo-design.md` (linhas 18–23 e
38–40) declara explicitamente fora do núcleo: **#3 Mutabilidade avançada** — pausa/retomada
(`RecurrenceStatus` hoje só tem `ACTIVE|CANCELLED`), edição "desta em diante" (o problema da
Netflix, seção 2.2 do PRD `2026-06-25-motor-de-recorrencia.md`), capping fim-de-mês 31→28
(hoje `BYMONTHDAY=31` é aceito com semântica de "pular fevereiro"; o clamp não-padrão foi
adiado) e detach formal; **#4 Simulador "E se...?"** — fantasma na timeline + `scenario_id` +
projeção híbrida (PRD próprio no futuro). O sub-projeto #2 (migração do planejamento) **já foi
entregue** (V21 + spec `2026-06-29-recorrencia-migracao-planejamento-design.md`) — não conte
ele como aberto.

**(b) Ativo deste projeto.** O corte núcleo/#3/#4 já está reconciliado por escrito na spec, com
cada corte justificado (ex: "Regra B invisível" provavelmente desnecessária porque o passado já
é imutável via `transactions`). Pouquíssimos projetos têm a fronteira do próprio motor mapeada
com esse nível de precisão antes de atacá-la.

**(c) Três primeiros passos.**
1. Reler as linhas 18–23 e 254–266 da spec do núcleo (tabela de cortes) e escolher a menor
   fatia do #3: pausa/retomada (adicionar `PAUSED` ao `RecurrenceStatus` + filtro na projeção)
   é a de menor superfície.
2. Spec SDD nova em `docs/superpowers/specs/` via change-control, incluindo a migration da
   constraint de `status` em `recurrence_rules`.
3. Testes discriminantes antes do código: regra `PAUSED` não gera fantasma na janela; retomada
   volta a projetar a partir do slot seguinte; para o capping, `BYMONTHDAY=31` em fevereiro
   projeta dia 28 (comportamento hoje inexistente — o teste falha primeiro, por construção).

**(d) Você tem um resultado quando…** a suíte contém os testes do passo 3 passando **e** um
teste de regressão provando que regras `ACTIVE` e `CANCELLED` projetam exatamente como antes
(mesma contagem de fantasmas na mesma janela do dataset seed, número fixado no teste).

## 4. JWT em cookie httpOnly (issue #91 — aberta; spec aprovada, NÃO implementada)

**Status real (verificado 2026-07-04):** a spec
`docs/superpowers/specs/2026-06-17-jwt-httponly-cookie-design.md` existe com status "Aprovado"
(abordagem: cookie httpOnly + endpoint `/auth/me`), mas **nada foi implementado**: zero
ocorrências de `Set-Cookie`/`ResponseCookie`/`httpOnly` em `backend/src/main/java/`, e o
frontend segue com `localStorage` em `frontend/src/app/core/services/auth.ts` (linhas 61, 67,
76) e header `Authorization` manual em `core/interceptors/auth.interceptor.ts`.

**(a) Por que a abordagem atual é insuficiente.** JWT em `localStorage` é legível por qualquer
script na página — um XSS exfiltra o token de um SaaS financeiro. Cookie `httpOnly` tira o
token do alcance do JavaScript por construção.

**(b) Ativo deste projeto.** A avaliação de viabilidade já foi feita e aprovada (spec de
2026-06-17 lista os pontos de toque); a issue #91 já enumera o escopo por camada; e o projeto
tem testes de controller com `spring-security-test` para ancorar o fluxo novo.

**(c) Três primeiros passos.**
1. Registrar na issue #91 a decisão de abordagem (critério da própria issue: comentário antes
   de implementar) — a spec aprovada já aponta cookie + `/auth/me`; confirmar ou revisar.
2. Backend primeiro, em worktree: `Set-Cookie` no login (`HttpOnly; Secure; SameSite`),
   `/auth/me`, `/auth/logout`, CORS com `credentials` — cada endpoint entra por
   `api-spec/openapi.yaml` + `./scripts/api-sync.sh` (spec-first).
3. Frontend: remover as três linhas de `localStorage` de `core/services/auth.ts`, ajustar
   `auth.interceptor.ts` (não anexa mais `Authorization`) e o `authGuard` (expiração via
   `/auth/me`).

**(d) Você tem um resultado quando…** `grep -rn "localStorage" frontend/src/app/core/` retorna
0 ocorrências, um teste E2E/integração verifica que `document.cookie` **não** contém o token, e
os testes de integração do fluxo login → `/auth/me` → logout passam — três asserções
mecânicas, nenhuma inspeção manual.

## 5. Tela de Patrimônio Total (issue #90 — aberta; `countInNetWorth` sem consumidor)

**(a) Por que a abordagem atual é insuficiente.** O campo `count_in_net_worth` existe desde a
V5 e **nenhuma query o consome** (verificado 2026-07-04: nenhuma referência fora de
entidade/DTO em `backend/src/main/java/`). O dashboard só responde "quanto tenho disponível"
(`countInLiquidBalance`); a pergunta "quanto eu valho" não tem resposta no sistema. O risco
documentado na issue #90: implementar sem antes fixar que **cartão de crédito com
`countInNetWorth=true` é passivo, não ativo** (faturas OPEN/CLOSED entram com sinal invertido).

**(b) Ativo deste projeto.** A issue #90 já traz o rascunho das queries; a semântica
liquidez vs. patrimônio já está formalizada (`fintech-domain-reference`); e o dataset Família
Costa permite calcular o patrimônio esperado à mão antes de escrever o endpoint.

**(c) Três primeiros passos.**
1. Cumprir o critério da #90 antes de codar: documentar a semântica (CREDIT_CARD = passivo) e
   a fórmula na casa certa (ver `fintech-core-docs-and-writing` para onde cada fato mora).
2. Calcular à mão, a partir do seed, o patrimônio esperado da Família Costa e **escrever esse
   número no teste antes** da query existir (disciplina de experimento, abaixo).
3. Endpoint spec-first (`api-spec/openapi.yaml` → `./scripts/api-sync.sh`) + query irmã de
   `sumNetLiquidBalanceByTenant` no repositório, com o branch de passivo para cartão.

**(d) Você tem um resultado quando…** o teste de integração asserta que o endpoint retorna
exatamente o valor pré-calculado do seed (centavo a centavo), incluindo o caso de cartão com
fatura aberta reduzindo o patrimônio.

*Adjacente de roadmap, não fronteira:* gráficos no dashboard (evolução mensal, breakdown por
categoria/conta — CLAUDE.md, roadmap aberto). É frontend consumindo dados existentes; entra
pelo change-control comum, sem experimento. Registrado aqui só para não ser confundido com
item de pesquisa. Nota: relatórios anuais/gráficos de evolução em escala real dependem do
item 2 (`effective_date`).

## 6. ADRs dormentes: Stripe Billing (ADR-002) e Fly.io (ADR-003)

**Status real (verificado 2026-07-04):** os dois ADRs dizem "Aceito", mas **nenhum tem
execução**: zero referências a Stripe no backend/frontend/pom, nenhum `fly.toml` no repo — e o
deploy real é Railway (backend) + Netlify (frontend) + Neon (Postgres), o que **contradiz** o
ADR-003. A premissa do ADR-002 ("o MVP precisa cobrar em 30 dias") não se materializou: o
projeto segue sem usuários externos. Rótulo honesto: **decisões registradas e dormentes**, não
trabalho em andamento.

**(a) Por que a abordagem atual é insuficiente.** ADR "Aceito" que a realidade já contornou é
documentação errada — quem chegar pelo mapa de decisões vai planejar em cima de infra que não
existe.

**(b) Ativo deste projeto.** O hábito de ADR já existe e o custo de reconciliar é uma sessão.
Se o dono quiser habilidade de fronteira em billing, o Stripe test mode dá um laboratório
completo (webhooks, idempotência) sem usuário real.

**(c) Três primeiros passos.**
1. Reconciliar o ADR-003: ou atualizar o status para superado/substituído registrando a infra
   real (Railway/Netlify/Neon), ou abrir a migração de verdade — decidir, não deixar ambíguo.
2. Revisitar o ADR-002 com a pergunta honesta: "cobrar alguém nos próximos 6 meses?" Se não,
   registrar o adiamento no próprio ADR com data.
3. Só se a resposta for sim: spike em worktree com Stripe test mode — endpoint
   `/api/webhooks/stripe` com verificação de assinatura e idempotência por `stripeEventId`
   (arquitetura já desenhada no ADR-002).

**(d) Você tem um resultado quando…** os dois ADRs têm status que bate com
`git remote -v` + ausência/presença de `fly.toml` e de código Stripe (verificável por grep); e,
no caso do spike, um `checkout.session.completed` disparado 2x pelo Stripe CLI produz
**exatamente 1** registro processado (idempotência assertada em teste).

---

## Disciplina de experimento

Como uma intuição vira mudança aceita AQUI. Ciclo completo, curto, executável por um modelo
classe-Sonnet sem contexto prévio:

**1. Hipótese escrita que PREVÊ números — antes de rodar qualquer coisa.**
Escreva em um arquivo de rascunho (ou direto na spec SDD embrionária): *"Prevejo que X medirá
Y."* Exemplos reais desta página: "a query sem filtro de tenant retornará 0 linhas com RLS
ativo"; "a lista paginada por `effective_date` será idêntica elemento a elemento à ordenação em
memória"; "o patrimônio da Família Costa dará R$ N,NN". Sem número previsto, não é experimento
— é passeio.

**2. Experimento em worktree isolada, partindo de baseline verde.**
Worktree derivada de `develop` (fluxo em `fintech-core-change-control`), e **antes** de mudar
qualquer coisa: baseline verde (regra completa em `fintech-core-validation-and-qa`). O
experimento só é interpretável se a única variável for a sua mudança.

**3. Comparação previsto vs. observado, por escrito.**
Rode a medição e registre os dois números lado a lado. Três saídas possíveis: bateu (hipótese
sobrevive), não bateu (hipótese morta ou modelo mental errado — igualmente valioso), bateu
parcialmente (refinar a hipótese e repetir o ciclo, não "aceitar mais ou menos").

**4. Todo experimento termina em um de dois destinos — nunca em limbo.**
- **Adoção:** o resultado vira spec em `docs/superpowers/specs/` (ou ADR em `docs/adr/` se for
  decisão estrutural) e a mudança entra pela rota normal — spec → plano → aprovação do dev →
  worktree → merge em `develop` → PR (ver `fintech-core-change-control`; esta skill nunca é
  atalho para pular o portão).
- **Aposentadoria documentada:** spec/ADR com status registrando por que não seguiu (com os
  números observados), issue fechada com o racional no comentário final, worktree removida.
  Uma hipótese morta e documentada vale mais que dez ideias vagas vivas — é ela que impede o
  próximo agente de re-explorar o mesmo beco.

---

## Proveniência e manutenção

Gerada em 2026-07-04 por auditoria do repo + issues; revisada em 2026-07-05 (cross-refs de
casa-única e roteamento do #85). Fatos voláteis datados no corpo.
Re-verificação de uma linha por afirmação:

- Issues ainda abertas: `gh issue view 116 85 91 90 -R sergiohcosta/fintech-core --json state 2>/dev/null || for i in 116 85 91 90; do gh issue view $i -R sergiohcosta/fintech-core | head -4; done`
- RLS ainda ausente: `grep -rni "POLICY" backend/src/main/resources/db/migration/`
- Ordenação ainda em memória: `grep -n "effectiveSortDateDto" backend/src/main/java/com/fintech/api/service/TransactionService.java`
- JWT ainda em localStorage: `grep -rn "localStorage" frontend/src/app/core/services/auth.ts`
- Cookie ainda não implementado: `grep -rni "ResponseCookie\|Set-Cookie" backend/src/main/java/`
- `countInNetWorth` ainda sem consumidor: `grep -rn "countInNetWorth" backend/src/main/java/ | grep -v "domain\|dto"`
- Stripe/Fly ainda dormentes: `grep -rli stripe backend/ frontend/src/ ; ls fly.toml`
- Última migration (numerar a próxima): `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`

Ao fechar qualquer issue citada, entregar um item ou reconciliar um ADR: atualizar esta skill
na mesma sessão (ritual em `fintech-core-docs-and-writing`). Item entregue sai da fronteira —
esta lista só tem valor enquanto for curta e verdadeira.
