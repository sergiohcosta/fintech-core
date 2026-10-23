# Impeccable Heuristic Critique: Import & Reconciliation Staging

**Target:** `frontend/src/app/features/import/`  
**Surfaces:** `import.html`, `import.scss`, `import-history.html`, `import-history.scss`  
**Timestamp:** `2026-10-23T14:27:00Z`  
**Mode:** `Operate` (Financial SaaS)  
**Evaluator:** Impeccable Design Lead  

---

## 1. Heuristic Score & Baseline

| Dimension | Score (1-5) | Primary Finding |
| :--- | :---: | :--- |
| **Hierarchy & Scannability** | 3/5 | Dropzone possui parágrafo longo e denso em vez de cards visuais com os formatos suportados (.OFX, .CSV, .PDF, comprovantes). |
| **Color & Material Theme Tokens** | 2/5 | Cores hexadecimais legadas (`#1b5e20`, `#b00020`, `#3f51b5`, `#e3f2fd`, `#1565c0`, `#fff3e0`, `#e65100`, etc.) e `rgba(63, 81, 181, ...)` que quebram o tema escuro. |
| **Spacing, Rhythm & Touch Targets** | 3/5 | Ações na tabela aglomeradas; toolbar de ações em massa com padding tímido. |
| **Interactive & State Design** | 4/5 | Excelente lógica de negócio (resolução de conflito 409, bulk actions locais, expansão de detalhe, gate de commit). |
| **Typography & Tabular Numerals** | 2/5 | Valores monetários na tabela e na revisão usam fonte proporcional sem `font-variant-numeric: tabular-nums`. |
| **Mobile Ergonomics** | 2/5 | Tabela com 8 colunas gera scroll horizontal excessivo em telas menores; faltam adaptações compactas. |
| **Accessibility (a11y) & Contrast** | 3/5 | Badges de confiança (high/low) e chips de status com contraste insuficiente no modo escuro. |
| **Consistency with DESIGN.md** | 2/5 | Violação da regra de purga de cores e ausência da tipografia mono nos valores contábeis. |
| **Total Baseline Score** | **21/40 (Acceptable)** | **Requer plano de modernização P0 + P1 + P2** |

---

## 2. Issues Inventory

### P0 (Crítico: Cores Hardcoded & Tema Escuro)
1. **Purga em `import.scss`**:
   - `.conf-badge.high`: `#1b5e20`, `rgba(46, 125, 50, 0.12)`.
   - `.conf-badge.low`: `#b00020`, `rgba(176, 0, 32, 0.12)`.
   - `.dropzone.dragging`: `border-color: var(--mat-sys-primary, #3f51b5); background-color: rgba(63, 81, 181, 0.06);`.
   - `.bulk-toolbar`: `background-color: rgba(63, 81, 181, 0.08);`.
2. **Purga em `import-history.scss`**:
   - Status chips: `#e3f2fd` / `#1565c0` (Extracted), `#fff3e0` / `#e65100` (Reviewed), `#e8f5e9` / `#2e7d32` (Committed), `#ffebee` / `#c62828` (Failed).
   - Textos: `#666`, `#595959`.

### P1 (Hierarquia & Dropzone Scannable)
3. **Dropzone Redesenhada**:
   - Substituir a parede de texto por chips ou badges visuais claros dos formatos suportados:
     - 📄 **Extratos:** OFX & CSV bancários
     - 💳 **Faturas:** PDF digital com texto
     - 🧾 **Comprovantes:** Imagens (JPG, PNG) e PIX
   - Feedback tátil e visual ao arrastar arquivos.
4. **Tipografia Monospaçada Tabular**:
   - Aplicar `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-variant-numeric: tabular-nums;` nas colunas de Valor e Data da tabela e no card de comprovante único.

### P2 (Toolbar em Massa & Acessibilidade)
5. **Bulk Toolbar Expressiva**:
   - Estilizar a barra de ações em lote como uma barra flutuante destacada com `var(--mat-sys-primary-container)` e botões nítidos.
6. **Status & Badges Acessíveis**:
   - Utilizar `color-mix()` sobre `var(--mat-sys-surface)` para garantir taxa de contraste mínima de 4.5:1 (WCAG AA) em ambos os temas.
