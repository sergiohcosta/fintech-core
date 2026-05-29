# Design: Melhorias no Formulário de Categorias

**Data:** 2026-05-29  
**Issues:** #20 (herdar cor/ícone do pai), #22 (grid de ícones)  
**Escopo:** Frontend only — `category-form.ts` e `category-form.html`

---

## Issue #22 — Grid expansível de ícones

### Problema
A seleção de ícone usa `mat-select` com lista vertical. Com 25 ícones, o dropdown é longo e pouco visual — o usuário não consegue comparar ícones facilmente.

### Solução
Substituir o `mat-select` por um **painel expansível customizado** (accordion):
- O campo mostra o ícone atualmente selecionado + nome
- Clicar no campo abre um grid de ícones abaixo
- Clicar num ícone do grid: define o valor no form e fecha o picker
- Clicar no trigger novamente: fecha sem mudar

### Componente

**`category-form.ts`**
- Novo signal: `iconPickerOpen = signal(false)`
- Novo método: `selectIcon(icon: string)` — faz `setValue` e fecha o picker
- Remove a necessidade de `MatSelectModule` para o campo de ícone (pode ser mantido para `parentId`)

**`category-form.html`**
- Remove `<mat-form-field>` + `<mat-select>` do ícone
- Substitui por bloco HTML customizado com trigger + grid condicional (`@if (iconPickerOpen())`)
- Grid: 5 colunas, botões com `mat-icon`, ícone selecionado destacado com cor primária

**`category-form.scss`**
- Adiciona estilos: `.icon-picker`, `.icon-picker-trigger`, `.icon-picker-grid`, `.icon-btn`, `.icon-btn.selected`

### Restrições
- Sem novos imports de Material além dos já presentes (`MatIconModule`)
- Grid responsivo: mínimo 4 colunas em mobile

---

## Issue #20 — Herdar cor e ícone do pai

### Problema
Ao selecionar uma categoria pai, os campos cor e ícone permanecem com seus defaults, sem refletir a herança visual da hierarquia.

### Solução
Quando o usuário seleciona um pai, os campos `color` e `icon` são **preenchidos automaticamente com os valores do pai e desabilitados**, sinalizando herança explícita. Ao remover o pai, os campos voltam ao padrão e ficam editáveis.

### Componente

**Interface `CategoryOption`**
```ts
interface CategoryOption {
  id: string;
  name: string;
  level: number;
  color: string;   // adicionado
  icon: string;    // adicionado
}
```

**`category-form.ts`**
- Novo signal: `inherited = signal(false)`
- `flattenCategories`: passa `color` e `icon` para cada `CategoryOption`
- Após `initForm()`, escuta `form.get('parentId')!.valueChanges` (Observable RxJS — correto para formulários reativos):
  - Se `parentId` não-nulo: busca pai em `parentOptions()`, copia `color`/`icon` via `patchValue({emitEvent: false})`, desabilita ambos os campos, seta `inherited(true)`
  - Se `parentId` nulo: reabilita os campos, restaura defaults (`folder` / `#3f51b5`), seta `inherited(false)`
- `onSubmit()`: usa `this.form.getRawValue()` em vez de `this.form.value` — campos `disabled` ficam fora do `.value` padrão, mas são incluídos no `getRawValue()`
- `checkEditMode()`: usa `patchValue(cat, { emitEvent: false })` para não disparar a lógica de herança ao carregar dados existentes

**`category-form.html`**
- Campo de ícone (e cor): quando `inherited()`, exibe `mat-hint` com "Herdado de [nome do pai]"

### Restrições
- Modo edição: herança não é re-aplicada ao carregar — respeita os valores já salvos
- Se o backend retornar uma categoria com pai cujos ícone/cor diferem dos valores salvos na filha, o formulário mostra os valores da filha (sem sobrescrever)

---

## Arquivos alterados

| Arquivo | Mudança |
|---|---|
| `category-form.ts` | `CategoryOption` + signal `iconPickerOpen` + signal `inherited` + lógica de herança + `selectIcon()` + `getRawValue()` |
| `category-form.html` | Substituição do `mat-select` de ícone + hints de herança |
| `category-form.scss` | Estilos do icon picker |

Nenhum arquivo novo. Nenhuma mudança de backend.
