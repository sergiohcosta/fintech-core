# Índice da Biblioteca de Skills — fintech-core

> **Documento de escopo vinculante.** Cada fato estrutural ("load-bearing") tem UMA casa.
> Skills irmãs fazem referência cruzada (`ver fintech-core-xxx`) — nunca reafirmam o fato.
> Gerado em 2026-07-04 a partir de auditoria completa do repositório.

## Contexto do projeto (para calibrar tudo)

Projeto SaaS de gestão financeira multi-tenant, **desenvolvedor único, orientado a aprendizado**
(CLAUDE.md define contrato de mentoria). Sem usuários externos, sem histórico de incidentes de
produção. Stack real verificada: Java 21 + Spring Boot 4.0.1 (não 3.4), Angular 21 Zoneless/Signals,
PostgreSQL 16 + Flyway (V1–V21), OpenAPI spec-first + Orval, JWT, GitHub Actions CI, deploy
auto (Railway backend + Netlify frontend + Neon Postgres). Não existe homelab/Kubernetes,
não existe multi-moeda/fx_rate, não existe tabela de snapshots de saldo — saldo é sempre
calculado. A biblioteca reflete o repo real, não um projeto imaginado.

## Público-alvo

Engenheiro júnior/pleno ou modelo classe-Sonnet, contexto zero. Voz de runbook imperativo,
comandos copy-paste, jargão definido uma vez, tabelas e checklists. Cada skill diz quando
NÃO usá-la e qual irmã usar.

---

## Lista final de skills (12)

### 1. `fintech-core-change-control`
Como mudanças são classificadas, gateadas e revisadas AQUI: ciclo SDD (spec → plano → aprovação →
worktree → execução → merge develop → PR para main), regras invioláveis com o *porquê* de cada uma
(spec-first, migrations imutáveis, dataset como parte da entrega, defesa em profundidade,
worktree por agente), convenções de commit PT-BR sem co-autoria. É o portão pelo qual toda
outra skill roteia mudanças de comportamento.

### 2. `fintech-core-debugging-playbook`
Tabela sintoma→triagem dos modos de falha reais deste projeto + "batalhas resolvidas"
(failure-archaeology absorvida aqui): checksum Flyway, drift do Orval, INNER JOIN implícito
do Hibernate, pitfalls Zoneless, principal detached, test-classpath do Flyway, loops de
tentativa-erro operacionais já auditados. Cada armadilha com sua história e o experimento
discriminante.

### 3. `fintech-core-architecture-contract`
As decisões de design estruturais e POR QUÊ, e os invariantes que devem valer (isolamento de
tenant acima de tudo). Pontos fracos conhecidos declarados abertamente (issues #85–#88, clusters
de bugs da auditoria 2026-07). Contrato, não tutorial.

### 4. `fintech-domain-reference`
O pacote de teoria de domínio que um pleno não tem, COMO SE APLICA AQUI: semântica de saldo
(só PAID), ciclo de fatura de cartão (closingDay/dueDay/resolveInvoiceMonth), parcelamento,
recorrência RRULE (regra vs. transação), matemática do ciclo de planejamento (Modelo A),
modelo de multi-tenancy (schema compartilhado, FK tenant_id, RLS como candidato). Não é livro-texto.

### 5. `fintech-core-config-and-flags`
Catálogo de todo eixo de configuração: perfis Spring (dev/local/prod), properties, variáveis de
ambiente (Railway/Netlify/Neon), environments Angular, `.env` dos scripts. Defaults, guards,
checklist para adicionar um novo eixo, comandos de re-verificação.

### 6. `fintech-core-build-and-env`
Recriar o ambiente de dev do zero: versões (JDK 21, Node 22, npm 11), docker compose, seeds,
sync de banco, armadilhas conhecidas de setup e ordem de regeneração de código.

### 7. `fintech-core-run-and-operate`
Rodar localmente e o pipeline de deploy até onde este repo define: anatomia dos comandos,
workflow de migration, deploy automático (push em main → Railway/Netlify), sync-tenant,
onde cada saída aterrissa, logging MDC em operação.

### 8. `fintech-core-validation-and-qa`
O que conta como evidência aqui: convenções de teste (unit/Mockito, integração/Testcontainers,
Vitest com lógica pura fora do TestBed), como adicionar testes, gates de CI, SonarQube,
alvo de cobertura 80%+ em lógica de negócio, dataset de testes como inventário dourado.

### 9. `fintech-core-docs-and-writing`
Manter os documentos de registro: mapa fonte-única (openapi.yaml, summary.md, domain.md,
database-schema.md, specs SDD, ADRs, git), templates, estilo da casa (PT-BR, identificadores
em inglês, sem verbos abrasileirados), ritual de encerramento de sessão.

### 10. `fintech-core-bug-backlog-campaign`
**Campanha executável e gateada** para o problema vivo mais difícil: o backlog de ~18 bugs da
auditoria de 2026-07 (#135–#152) + o bloqueio arquitetural `effective_date` (#85). Fases
numeradas por cluster de causa-raiz (correção monetária, concorrência, integração
recorrência↔planejamento, segurança, frontend), comandos exatos, observações esperadas em
cada gate, menu de soluções ranqueado, caminhos errados cercados, protocolo de validação e
promoção via change-control.

### 11. `fintech-core-proof-and-analysis-toolkit`
"Prove, não confie": receitas com exemplo real do repo para demonstrar isolamento de tenant
em nível de query, verificar correção de cálculo de saldo, demonstrar a race condition de
`getOrCreate`, provar invariantes de soma de parcelas (centavos), verificar expansão RRULE
contra a spec RFC 5545.

### 12. `fintech-core-research-frontier`
Problemas abertos onde o projeto pode genuinamente avançar (ou onde o dono quer construir
habilidade de fronteira), CADA UM com: por que a abordagem atual é insuficiente, o ativo
específico deste projeto, os 3 primeiros passos concretos NESTE repo, e o marco falsificável
"você tem um resultado quando…". Inclui a **disciplina de experimento** (research-methodology
absorvida aqui): hipótese prevê números antes de rodar; ciclo de vida experimento → mudança
adotada ou aposentadoria documentada. Lista curta e honesta: RLS (#116), effective_date (#85),
sub-projetos de recorrência, JWT httpOnly (#91), Stripe (ADR-002), Fly.io (ADR-003).

---

## Casa única por classe de fato (vinculante)

| Classe de fato | Casa única | Observação |
|---|---|---|
| Ciclo SDD, regras de worktree/branch/PR, convenção de commit | change-control | git-operator.md é a fonte; skill explica o porquê |
| Migrations imutáveis + como nomear/criar migration | change-control | debugging só cobre o SINTOMA de checksum |
| Dataset como parte da entrega (regra + tabela situação→ação) | change-control | validation-and-qa referencia |
| Pipeline Orval/api-sync.sh (como regenerar cliente) | build-and-env | debugging cobre sintomas de drift |
| Invariante de isolamento de tenant (enunciado + regra) | architecture-contract | proof-toolkit cobre COMO PROVAR |
| Semântica de saldo, ciclo de fatura, RRULE, Modelo A (teoria) | domain-reference | architecture-contract só lista a decisão + porquê |
| effectiveSortDate (regra de data efetiva) | domain-reference | |
| Perfis Spring, env vars, environments Angular | config-and-flags | run-and-operate referencia |
| Versões de toolchain (JDK/Node/npm) + setup do zero | build-and-env | |
| Comandos de rodar/deploy/sync-tenant/sync-db | run-and-operate | build-and-env cobre só o setup inicial |
| Convenções de teste + test-summary.sh + gates CI + Sonar | validation-and-qa | |
| Mapa de documentos de registro + estilo PT-BR | docs-and-writing | |
| Tabela sintoma→triagem + batalhas resolvidas | debugging-playbook | |
| Backlog de bugs 2026-07 + plano de ataque | bug-backlog-campaign | architecture-contract só lista como "ponto fraco" |
| Receitas de prova (tenant, saldo, race, centavos, RRULE) | proof-and-analysis-toolkit | |
| Problemas abertos de fronteira + disciplina de experimento | research-frontier | |
| Credenciais de dev/teste (carlos@costa.com etc.) | build-and-env | |
| Gotchas operacionais de agente (vitest cru, cwd, suíte 7min) | debugging-playbook | change-control referencia no fluxo |

## Categorias PULADAS ou FUNDIDAS (com evidência)

| Categoria da taxonomia | Decisão | Evidência |
|---|---|---|
| failure-archaeology | **Fundida** no debugging-playbook (seção "Batalhas resolvidas") | Falhas reais existem (gotchas do CLAUDE.md, memória de auditoria 2026-07-02) mas são ~10 itens — não sustentam skill própria |
| external-positioning | **Pulada** | Zero usuários externos, zero comunicação pública; nada no repo |
| research-methodology | **Fundida** na research-frontier (seção "Disciplina de experimento") | Projeto de dev único; a metodologia só faz sentido acoplada aos problemas de fronteira concretos |
| Kubernetes/homelab operate | **Pulada** (não existe) | Deploy é Railway/Netlify/Neon auto-deploy; nenhum manifesto k8s no repo |
| Multi-moeda / fx_rate / snapshots de saldo | **Pulada** (não existe no domínio) | Schema V1–V21 não tem moeda nem snapshot de saldo; saldo é sempre calculado |

## Regras de autoria (resumo vinculante)

- Prosa em PT-BR; comandos/código/paths/frontmatter na forma natural; `description` PT-BR com termos técnicos em inglês presentes.
- GROUND TRUTH ONLY: todo comando/flag/path verificado contra o repo antes de afirmar. Não verificável → omitir ou rotular "não verificado".
- Datar fatos voláteis (ex: "em 2026-07-04, 36 issues abertas"). Terminar com seção **Proveniência e manutenção** com comandos de re-verificação de uma linha.
- Formato: `.claude/skills/<name>/SKILL.md`, frontmatter YAML `name` + `description` rica em gatilhos.
- Nada contradiz CLAUDE.md; nenhuma skill roteia por fora do change-control.
- Escrever SOMENTE dentro de `.claude/skills/`. NENHUM comando git.
