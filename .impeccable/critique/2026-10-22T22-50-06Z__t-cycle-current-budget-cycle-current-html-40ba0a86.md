---
target: frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html
total_score: 19
max_score: 40
na_heuristics: 
p0_count: 1
p1_count: 2
target_identity: "file:/home/sergio/projetos/fintech-core/frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html"
target_fingerprint: "sha256:da73cbe499883a927cf714051b0d229618c0fbc8775b5cd6d62200e79d31d28b"
target_path: /home/sergio/projetos/fintech-core/frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.html
timestamp: 2026-10-22T22-50-06Z
slug: t-cycle-current-budget-cycle-current-html-40ba0a86
---
# Heuristics Scoring Guide

| # | Heuristic | Score | Key Issue |
|---|-----------|:---:|-----------|
| 1 | Visibility of System Status | 2/4 | Carregamento em texto puro; sem barra visual de tempo decorrido do ciclo vs. orçamento consumido. |
| 2 | Match System / Real World | 2/4 | Vazamento de enums crus do banco (`PENDING`, `REALIZED`); terminologia contábil fria ("Abertura"). |
| 3 | User Control and Freedom | 2/4 | Fechamento de ciclo irreversível; abertura de ciclo usa modal primitivo em vez do diálogo rico. |
| 4 | Consistency and Standards | 2/4 | `BudgetCycleOpenDialogComponent` existe pronto mas está órfão; traduções divergentes entre tabelas. |
| 5 | Error Prevention | 3/4 | Confirmação inline de remoção é excelente; falta validação de data dentro do intervalo do ciclo. |
| 6 | Recognition Rather Than Recall | 2/4 | Até 4 botões de ícone por linha sem texto; incompreensíveis no toque mobile. |
| 7 | Flexibility and Efficiency | 2/4 | Sem ações em lote para vincular não-planejados; sem edição direta de item de orçamento. |
| 8 | Aesthetic and Minimalist Design | 2/4 | Sem metáfora visual de envelopes; sobrecarga de tabelas abertas com mais de 12 ações concorrentes. |
| 9 | Error Recovery | 1/4 | Tratamento de erro descarta respostas do backend e mostra snackbars genéricos ("Erro. Tente novamente."). |
| 10 | Help and Documentation | 1/4 | Zero explicações contextuais sobre como o limite diário é recalculado ou o que fazer em caso de déficit. |
| **Total** | | **19/40** | **Poor (12–19)** |

## Design Specificity Verdict

**LLM Assessment:** O módulo de Planejamento opera atualmente como uma *"Planilha de auditoria disfarçada de planejamento"*. Apesar de o `PRODUCT.md` e o `DESIGN.md` definirem o produto como um "Porto Seguro Familiar" e rejeitarem a frieza de tabelas sobrecarregadas, a tela entrega literalmente zero envelopes visuais, zero barras de progresso de categorias e até 4 tabelas densas simultâneas. O indicador essencial do produto—o `dailyAllowance` (Limite Diário Seguro)—está escondido no quarto cartão de uma grade de três colunas, nivelado com totais contábeis passados.

**Deterministic Scan:** 0 findings (`[]`). Os estilos dos componentes de planejamento respeitam as regras estáticas de sintaxe e tokens, mas a composição e a experiência de usuário estão profundamente aquém do potencial do produto.

**Visual Overlays:** Ambiente headless sem display gráfico ou DevTools CDP; validação realizada diretamente via AST e revisão heurística.

## Overall Impression
O planejamento possui uma engenharia contábil sofisticada (decomposição matemática do saldo, tratamento de despesas não planejadas e projeção RRULE), mas a interface intimida o usuário leigo com jargões e tabelas de ERP. O módulo precisa transformar números frios em **envelopes visuais**, colocar o **Limite Diário Seguro no topo como Hero**, e resgatar o diálogo rico de abertura de ciclo que já foi codificado mas está esquecido.

## What's Working
1. **Transparência Matemática do Breakdown (`buildBreakdown`):** A equação visual `Caixa Líquido + Receitas - Despesas = Saldo Seguro` no diálogo/rota de composição é exemplar para desmistificar a contabilidade.
2. **Padrão de Confirmação Inline de Remoção:** O fluxo de confirmação em duas etapas na própria linha (`Remover? [check] [close]`) evita modais intrusivos e protege contra cliques acidentais.
3. **Seção de Despesas Não Planejadas:** Capturar gastos fora da previsão em uma seção dedicada com conversão em 1 clique (`createPlannedFromUnplanned`) respeita a imprevisibilidade da vida real sem corromper o orçamento.

## Priority Issues
- **[P0] Diálogo Completo de Abertura de Ciclo Órfão:** `BudgetCycleOpenDialogComponent` com cálculo preditivo e pré-visualização existe pronto no código, mas `budget-cycle-current.ts` abre um modal primitivo com dois selects. (*Fix: Reconectar `openCycle()` ao diálogo completo; Comando: `/impeccable onboard`*).
- **[P1] Submersão do Limite Diário Seguro (Hero Metric):** `dailyAllowance` e `availableToSpend` estão escondidos no final de uma grade secundária de cartões. (*Fix: Promover a Hero Banner com barra de progresso temporal e teto diário destacado; Comando: `/impeccable bolder`*).
- **[P1] Falha de Ergonomia e Touch Targets no Mobile:** Até 4 botões de ícone minúsculos por linha (`link`, `link_off`, `event_repeat`, `delete_outline`) sem rótulo textual, inviáveis no celular. (*Fix: Adicionar menu contextual de ações e cards no mobile; Comando: `/impeccable adapt`*).
- **[P2] Vazamento de Enums Crus do Banco na UI:** Chips exibindo `PENDING`, `REALIZED`, `INSTALLMENT`, `SKIPPED`. (*Fix: Criar pipe/função de localização em português com cores semânticas; Comando: `/impeccable polish`*).
- **[P2] Fechamento Frio de Ciclo ("Cliffhanger"):** O encerramento do ciclo congela a tela sem comemoração de economia ou transição para o próximo mês. (*Fix: Criar modal de retrospectiva com saldo poupado e CTA "Iniciar Próximo Mês"; Comando: `/impeccable delight`*).

## Persona Red Flags
- **Alex (Power User):** Reconciliar 10 transações não planejadas exige 10 aberturas de modais individuais; sem suporte a ações em lote ou edição direta de valores na tabela.
- **Jordan (Iniciante):** Fica paralisado diante de termos como *"Saldo de Abertura"*, *"Realizado em caixa"* e chips *"PENDING"*.
- **Casal / Família:** Zero atribuição de quem criou ou executou o item de despesa no orçamento doméstico.

## Minor Observations
- Loading renderizado como `<p>Carregando...</p>` em vez de skeleton ou spinner.
- Inline styling `style="font-size:0.75rem;color:var(--mat-sys-error)"` no template.
- Ausência de filtros ou busca quando a lista de categorias planejadas cresce.

## Questions to Consider
1. Por que manter o diálogo rico de abertura de ciclo órfão se ele já calcula exatamente a equação de saldo inicial e despesas recorrentes?
2. Como podemos transformar tabelas de auditoria em envelopes visuais com barras de progresso de consumo por categoria?
3. O que falta para transformar o fechamento de ciclo em um momento gratificante de celebração do quanto o usuário economizou no mês?
