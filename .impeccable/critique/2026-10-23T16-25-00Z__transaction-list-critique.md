# Impeccable Design Critique: Transactions Surface & Filters

**Target:** `frontend/src/app/features/transaction/transaction-list/` & `transaction-timeline/`  
**Timestamp:** `2026-10-23T16:25:00Z`  
**Mode:** `Operate` (Financial SaaS)  
**Method:** Dual isolated sub-agents (Assessment A: `be05186f` | Assessment B: `dc07f04a`)  

---

## 1. Executive Summary & Quality Score

| Dimension | Score (1-5) | Primary Finding |
| :--- | :---: | :--- |
| **Hierarchy & Scannability** | 2/5 | **"Pill Fatigue" severa:** 9 colunas na tabela com até 5 badges coloridos por linha competindo visualmente; falta um resumo financeiro do período selecionado. |
| **Color & Material Tokens** | 1/5 | **Drift de Marca & Quebra Semântica:** Mais de 40 cores hexadecimais legadas (`#1a73e8`, `#4285f4`, `#e8f0fe`) no estilo "Google Blue"; violação da regra de não usar a cor primária da marca para representar receita na timeline. |
| **Spacing & Touch Targets** | 2/5 | Botão de remover filtro com apenas **16×16px** (falha WCAG 2.5.5); marcadores de timeline com 28×28px; micro-tipografia ilegível de 9px em `transaction-filters.scss`. |
| **Interactive & State Design** | 3/5 | Excelentes recursos contábeis (gestão de parcelamento, deduplicação, ghost rows de recorrência, ordenação multi-coluna com Shift+Click), mas **zero feedback de carregamento** (tabela congela sem spinner ou skeleton). |
| **Typography & Tabular Numerals** | 2/5 | Valores da tabela usam `tabular-nums`, mas totais de período, faturas agrupadas, visualização em calendário e linha do tempo usam números proporcionais. |
| **Mobile Ergonomics** | 3/5 | Modo card mobile e action sheet são bons, mas o calendário de 7 colunas quebra em telas de 360–390px e o drawer de filtros possui elementos microscópicos. |
| **Accessibility (a11y) & Contrast** | 2/5 | Textos em 9px; cores de chips com contraste insuficiente no tema escuro; seleção de múltiplos meses depende de atalho oculto de teclado (`Ctrl`/`Cmd` + Click). |
| **Consistency with DESIGN.md** | 2/5 | Desrespeito à identidade "Cyan Equilíbrio" e violação da regra de invariante semântico financeiro. |
| **Total Quality Score** | **17/40 (Poor)** | **Requer plano de modernização e destilação visual** |

---

## 2. Destaques Positivos (Strengths)

1. **Rigor e Verdade Contábil Brasileira:**  
   O modelo de domínio é impecável: tratamento de parcelamento com contagem real (`paidInstallments / totalInstallments`), proteção de parcelas passadas já pagas na exclusão, lock pessimista e rastreamento de data da compra vs. data de vencimento da fatura.
2. **Motor de Recorrência Proativo (Ghost Rows):**  
   Integração elegante de regras RFC 5545 RRULE projetadas on-the-fly (`ghost-row`, "Prevista") permitindo confirmar ou pular lançamentos diretamente na tabela sem poluir a base contábil.
3. **Prevenção de Erros Avançada:**  
   `planBulkDelete` deduplica pernas de transferências casadas (double-entry); edição isolada de transferências é bloqueada com tooltips educativos.

---

## 3. Problemas Prioritários (P0, P1, P2)

### [P0] Violação do Invariante Semântico & Drift para "Google Blue"
- **Diagnóstico:** Em `transaction-timeline` subcomponents, `.income` usa `var(--mat-sys-primary)` (Cyan), e `transaction-filters.scss` usa `#1a73e8`, `#4285f4`, `#e8f0fe` (Google Blues) e `#1976d2` (Material 2).
- **Impacto:** Fere a regra absoluta de `DESIGN.md`: *"Jamais utilize a cor da marca para substituir sinais financeiros de débito ou crédito"*. A cor da marca se confunde com receita e o app parece um patchwork de produtos do Google.
- **Correção:** Purga total de cores hardcoded. Vincular receita exclusivamente a `var(--fintech-income, #10b981)`, despesa a `var(--fintech-expense, #ef4444)` e filtros à paleta Cyan Equilíbrio (`var(--mat-sys-primary)`).

### [P1] "Pill Fatigue" & Sobrecarga Cognitiva na Tabela
- **Diagnóstico:** A tabela desktop exibe 9 colunas onde cada linha contém até 5 badges coloridos simultâneos (Tipo, Status, Categoria, Parcela, Fatura) além do valor assinado colorido.
- **Impacto:** Fadiga visual. A coluna "Tipo" é totalmente redundante (o valor assinado `+` verde ou `-` vermelho já informa o tipo). Categoria e Conta devem ser textos sóbrios, reservando chips para exceções (ex.: `Pendente`, `Prevista`).

### [P1] Fragmentação das 6 Vistas & Navegação Assimétrica
- **Diagnóstico:** O usuário conta com 6 modos de visualização fragmentados: Tabela plana, Tabela agrupada por período, Tabela agrupada por fatura, Linha do tempo calendário, Linha do tempo lista agrupada e Linha do tempo horizontal. Além disso, sair da Lista para a Timeline limpa os filtros ativos.
- **Impacto:** Desorientação mental. Consolidação necessária em um seletor unificado (`[ Tabela | Agrupado | Calendário ]`) com persistência bidirecional de filtros via queryParams.

### [P1] Ausência de Feedback de Carregamento & Empty State Inerte
- **Diagnóstico:** Sem skeletons, spinners ou barra de progresso durante requisições HTTP na filtragem. Quando os filtros retornam 0 resultados, não há botão de "Limpar filtros" dentro do card vazio.

### [P2] Falhas de Acessibilidade & Alvos de Toque Críticos
- **Diagnóstico:** O botão `X` de remover chip de filtro mede **16×16px** (quase impossível de tocar no mobile). Textos de seção com **9px** em `transaction-filters.scss`. Calendário de 7 colunas sem breakpoint para mobile.
