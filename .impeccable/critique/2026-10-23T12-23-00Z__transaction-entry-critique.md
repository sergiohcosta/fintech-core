# Impeccable Heuristic Critique: Transaction Entry & Modals

**Target:** `frontend/src/app/features/transaction/`  
**Surfaces:** `transaction-form`, `transaction-quick-sheet`, `installment-confirm-dialog`, `delete-installment-dialog`  
**Timestamp:** `2026-10-23T12:23:00Z`  
**Mode:** `Operate` (Financial SaaS)  
**Evaluator:** Impeccable Design Lead  

---

## 1. Executive Summary & Heuristic Score

| Dimension | Score (1-5) | Primary Finding |
| :--- | :---: | :--- |
| **Hierarchy & Scannability** | 3/5 | O valor monetário não tem o devido destaque heroico no desktop form; transferência carece de conector visual direcional (De ➔ Para). |
| **Color & Material Theme Tokens** | 1/5 | **Crítico:** Dezenas de cores hexadecimais legadas (`#c62828`, `#1b5e20`, `#e65100`, `#ffebee`, `#e8f5e9`, etc.) e `rgba(0,0,0,...)` que quebram o tema escuro. |
| **Spacing, Rhythm & Touch Targets** | 3/5 | Quick sheet respeita 48px, mas os chips de prévia de parcelamento e a seção de propagação têm espaçamentos assimétricos. |
| **Interactive & State Design** | 3/5 | Boas features (avaliação de fórmulas `=`, incremento com setas na data), porém a quick sheet mobile usa input number bruto sem máscara monetária. |
| **Typography & Tabular Numerals** | 3/5 | Falta alinhamento estrito a `tabular-nums` e fonte mono nativa na prévia de parcelas e na quick sheet. |
| **Mobile Ergonomics (Quick Sheet)** | 3/5 | Boa base com drag handle e gestos, mas seletores de conta e categoria são secos e sem ícones representativos. |
| **Accessibility (a11y) & Contrast** | 2/5 | Alphas pretos hardcoded em modo escuro ficam invisíveis; contrastes de badges e toggles violam WCAG AA no dark mode. |
| **Consistency with DESIGN.md** | 2/5 | Desrespeito à regra de não usar cores hardcoded e falta do visual heroico no registro de transação. |
| **Total Baseline Score** | **20/40 (Acceptable)** | **Requer plano de refatoração P0 + P1 + P2** |

---

## 2. Issues Inventory

### P0 (Crítico / Quebra de Contraste e Tema)
1. **Purga de Cores Hardcoded em `transaction-form.scss`**:
   - Toggles de Tipo: `#ffebee` / `#c62828` (Expense), `#e8f5e9` / `#1b5e20` (Income).
   - Toggles de Status: `#fff8e1` / `#e65100` (Pending), `#e8f5e9` / `#1b5e20` (Paid), `#f5f5f5` / `#616161` (Cancelled).
   - Fundo fixo `#fff` em `.preview-chip` (glaring white no tema escuro).
   - Textos e bordas com `rgba(0, 0, 0, 0.45)`, `rgba(0, 0, 0, 0.55)`, `rgba(0, 0, 0, 0.65)`, `rgba(0, 0, 0, 0.85)` — invisíveis no dark mode.
2. **Purga de Cores Hardcoded em Diálogos de Parcelamento**:
   - `installment-confirm-dialog.scss`: `#e7c9a6`, `#75430f`, `#fff4e2`, `#fff7eb`, `#176b50`, `#925314`.
   - `delete-installment-dialog.scss`: `#fff8e1`, `#5f4000`, `#f9a825`, `rgba(0, 0, 0, 0.08)`.

### P1 (Hierarquia & Ergonomia Visual)
3. **Hero Amount no `transaction-form`**:
   - Transformar o campo de Valor em um bloco de destaque com tipografia de 2rem, prefixo `R$` refinado e suporte fluido a fórmulas.
4. **Direcionalidade em Transferência**:
   - No modo transferência, apresentar um conector visual claro entre a Conta de Origem e a Conta de Destino (`[Origem] ➔ [Destino]`) para evitar inversões acidentais.
5. **Prévia de Parcelamento Moderna**:
   - Transformar a lista de chips brancos em uma tabela compacta/cards de faturas com badges de vencimento e tipografia `tabular-nums`.

### P2 (Refinamento da Quick Sheet Mobile)
6. **Quick Sheet com Máscara e Feedback Semântico**:
   - Adicionar acentuação cromática dinâmica no toggle de Tipo (Vermelho suave para Despesa, Verde para Receita, Azul/Cyan para Transferência).
   - Exibir ícones de tipo de conta (`credit_card`, `account_balance`, `savings`) no seletor de contas da Quick Sheet.
