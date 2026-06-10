# Spec: Mapeamento de Taxonomia — Backfill para Tenants Existentes

**Data:** 2026-06-10
**Status:** Aprovado
**Motivação:** Tenants cadastrados antes da feature de seed automático (issue #71) possuem categorias sem `taxonomy_code`. Esta feature permite que o ADMIN mapeie manualmente suas categorias raiz aos códigos padrão da taxonomia.

---

## 1. Problema

A migration V11 adiciona a coluna `taxonomy_code` como nullable. Tenants existentes ficam com todas as categorias com `taxonomy_code = NULL`, o que os exclui do benchmarking futuro (roadmap item 5). O ADMIN precisa de uma forma de associar suas categorias às da taxonomia padrão.

### Escopo desta feature

- Apenas **categorias raiz** (`parent == null`, não arquivadas) precisam ser mapeadas manualmente
- Categorias filhas serão tratadas em etapa posterior (reset do dataset ou estratégia futura)
- Somente o usuário com role **ADMIN** pode realizar o mapeamento

---

## 2. Decisões de Design

### 2.1 UX: tela dedicada com interação click-to-connect

Rota `/categories/taxonomy` — tela exclusiva com dois painéis lado a lado:

- **Esquerda:** categorias raiz do tenant (não arquivadas)
- **Direita:** 14 códigos da taxonomia padrão

O usuário seleciona uma categoria na esquerda → o painel direito destaca o código **sugerido automaticamente** → clica para confirmar (ou escolhe outro).

### 2.2 Sugestão automática client-side

Tabela estática no componente Angular com mapeamento PT-BR → código:

```typescript
const SUGGESTIONS: Record<string, string> = {
  'renda': 'INCOME', 'receitas': 'INCOME', 'salario': 'INCOME',
  'moradia': 'HOUSING', 'casa': 'HOUSING',
  'alimentacao': 'FOOD', 'alimentação': 'FOOD', 'comida': 'FOOD',
  'transporte': 'TRANSPORT',
  'saude': 'HEALTH', 'saúde': 'HEALTH',
  'lazer': 'LEISURE', 'entretenimento': 'LEISURE',
  'educacao': 'EDUCATION', 'educação': 'EDUCATION',
  'roupas': 'CLOTHING', 'vestuario': 'CLOTHING', 'vestuário': 'CLOTHING',
  'casa & decoracao': 'HOME_GOODS', 'decoracao': 'HOME_GOODS',
  'assinaturas': 'SUBSCRIPTIONS',
  'cuidados pessoais': 'PERSONAL_CARE', 'beleza': 'PERSONAL_CARE',
  'pets': 'PETS', 'animais': 'PETS',
  'financeiro': 'FINANCIAL', 'financas': 'FINANCIAL', 'finanças': 'FINANCIAL',
  'presentes': 'GIFTS', 'doacoes': 'GIFTS', 'doações': 'GIFTS',
};
```

Normalização: `name.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')` antes de comparar.

Sem sugestão = categoria com borda laranja tracejada (ambígua).

### 2.3 Mapeamento 1:1

Um `taxonomy_code` pode ser atribuído a no máximo uma categoria raiz por tenant. Códigos já em uso aparecem desabilitados (não clicáveis) no painel direito.

Ao remapear uma categoria (clicar nela novamente), o código anterior é liberado.

### 2.4 Persistência: reusar `PUT /api/categories/{id}`

`taxonomyCode` é adicionado ao `CategoryUpdateDTO` como campo opcional (nullable). O botão "Salvar" dispara um `forkJoin` com uma chamada PUT por categoria alterada.

### 2.5 Controle de acesso — defesa em profundidade

O projeto segue a regra: restrições de role devem ser validadas **em ambas as camadas**.

**Frontend:**
- Não existe `adminGuard` de rota no projeto — o padrão adotado (ver `/team`) é:
  - Link no sidenav oculto via `@if (authService.isAdmin())` 
  - Componente redireciona para `/dashboard` se `!authService.isAdmin()` no `ngOnInit`

**Backend:**
- `CategoryService.update()` deve verificar `user.getRole() == UserRole.ADMIN` ao receber `taxonomyCode` não nulo — se não for ADMIN, lança `AccessDeniedException` (→ 403)
- Isso impede que um membro não-admin defina taxonomy codes via chamada direta à API, mesmo que tenha acesso ao endpoint PUT para editar nome/ícone/cor

---

## 3. Backend

### 3.1 `CategoryUpdateDTO`

Adicionar campo opcional:

```java
public record CategoryUpdateDTO(
    @NotBlank String name,
    @NotBlank String icon,
    @NotBlank String color,
    UUID parentId,
    String taxonomyCode   // novo — nullable, sem validação de enum (String livre)
) {}
```

`taxonomyCode` é `String` (não enum) para manter a mesma filosofia da entidade: não quebra desserialização com valores desconhecidos.

### 3.2 `CategoryService.update()`

Adicionar ao método `update()` após atualizar os demais campos:

```java
if (dto.taxonomyCode() != null) {
    if (user.getRole() != UserRole.ADMIN) {
        throw new AccessDeniedException("Somente ADMIN pode definir taxonomy_code");
    }
    category.setTaxonomyCode(dto.taxonomyCode());
}
// null = omitido no payload → não altera o valor existente
```

**Semântica "ausente = não alterar":** como Java records não distinguem "campo ausente" de "campo nulo", a regra é: `null` no DTO significa "não alterar". O formulário de edição de categoria existente nunca envia `taxonomyCode`, então o Jackson o preenche com `null` → o valor existente é preservado automaticamente.

Isso significa que um `taxonomy_code` já definido não pode ser removido via PUT (precisaria de `""` ou endpoint dedicado). Essa limitação é aceitável para o escopo atual.

`AccessDeniedException` do Spring Security é mapeada como 403 pelo `GlobalExceptionHandler` (ou pelo próprio framework).

### 3.3 `openapi.yaml` — `CategoryUpdateDTO`

Adicionar campo ao schema:

```yaml
CategoryUpdateDTO:
  type: object
  required: [name, icon, color]
  properties:
    name:
      type: string
    icon:
      type: string
    color:
      type: string
    parentId:
      type: string
      format: uuid
      nullable: true
    taxonomyCode:
      type: string
      nullable: true
      description: "Código de taxonomia padrão. null = não alterar valor existente."
```

---

## 4. Frontend

### 4.1 Arquivos novos

| Arquivo | Responsabilidade |
|---------|-----------------|
| `features/category/taxonomy-mapping/taxonomy-mapping.ts` | Componente principal da tela |
| `features/category/taxonomy-mapping/taxonomy-mapping.html` | Template |
| `features/category/taxonomy-mapping/taxonomy-mapping.scss` | Estilos |
| `features/category/taxonomy-mapping/taxonomy-suggestions.ts` | Tabela estática de sugestões + função de match |

### 4.2 Arquivos modificados

| Arquivo | Mudança |
|---------|---------|
| `app.routes.ts` | Adicionar rota `/categories/taxonomy` como filho do shell (sem guard adicional — controle no componente) |
| `components/shell/shell.ts` e `shell.html` | Adicionar link "Mapear taxonomia" visível apenas para `isAdmin()` |

### 4.3 Estado do componente

```typescript
// Signals
rootCategories = signal<CategoryResponseDTO[]>([]);
selectedCategory = signal<CategoryResponseDTO | null>(null);
pendingMappings = signal<Map<string, string>>(new Map()); // categoryId → taxonomyCode
saving = signal(false);

// Computed
usedCodes = computed(() =>
  new Set([
    ...rootCategories().filter(c => c.taxonomyCode).map(c => c.taxonomyCode!),
    ...pendingMappings().values(),
  ])
);

suggestedCode = computed(() => {
  const cat = selectedCategory();
  return cat ? getSuggestion(cat.name) : null;
});
```

### 4.4 Interação

1. **Selecionar categoria (esquerda):** `selectedCategory.set(cat)` → painel direito recalcula
2. **Clicar em código (direita):**
   - Não disponível se código está em `usedCodes()` (exceto se é o código da categoria selecionada — remapeamento)
   - `pendingMappings.update(m => m.set(cat.id, code))`
   - Avança automaticamente para a próxima categoria raiz sem `taxonomyCode` e sem entrada em `pendingMappings`
3. **Salvar:**
   - `forkJoin` das chamadas `PUT /api/categories/{id}` para todas as entradas em `pendingMappings`
   - Atualiza `rootCategories` com os valores salvos
   - Limpa `pendingMappings`

### 4.5 Exibição dos 14 códigos padrão no painel direito

Lista estática no componente (não vem da API):

```typescript
export const TAXONOMY_ROOTS = [
  { code: 'INCOME',        label: 'Renda',              icon: 'attach_money' },
  { code: 'HOUSING',       label: 'Moradia',             icon: 'home' },
  { code: 'FOOD',          label: 'Alimentação',         icon: 'restaurant' },
  { code: 'TRANSPORT',     label: 'Transporte',          icon: 'directions_car' },
  { code: 'HEALTH',        label: 'Saúde',               icon: 'favorite' },
  { code: 'LEISURE',       label: 'Lazer',               icon: 'movie' },
  { code: 'EDUCATION',     label: 'Educação',            icon: 'school' },
  { code: 'CLOTHING',      label: 'Vestuário',           icon: 'checkroom' },
  { code: 'HOME_GOODS',    label: 'Casa & Decoração',    icon: 'weekend' },
  { code: 'SUBSCRIPTIONS', label: 'Assinaturas',         icon: 'subscriptions' },
  { code: 'PERSONAL_CARE', label: 'Cuidados Pessoais',   icon: 'spa' },
  { code: 'PETS',          label: 'Pets',                icon: 'pets' },
  { code: 'FINANCIAL',     label: 'Financeiro',          icon: 'account_balance' },
  { code: 'GIFTS',         label: 'Presentes e Doações', icon: 'card_giftcard' },
];
```

---

## 5. Regras de UX

| Situação | Comportamento |
|----------|---------------|
| Categoria já mapeada (tem `taxonomyCode`) | Aparece com badge do código + pode ser resselecionada para remapear |
| Categoria sem sugestão clara | Borda laranja tracejada; painel direito não destaca nenhum código |
| Código já em uso por outra categoria | Desabilitado no painel direito (cursor not-allowed, opacidade reduzida) |
| Código é o da categoria selecionada | Habilitado para permitir confirmação ou troca |
| `pendingMappings` vazio | Botão Salvar desabilitado |
| Após salvar com sucesso | Snackbar "Mapeamento salvo"; `pendingMappings` limpo; `rootCategories` atualizado |

---

## 6. O que NÃO está no escopo

- Mapeamento de categorias **filhas** (tratado em etapa posterior)
- Validação de `taxonomyCode` como enum no backend (valor é livre — se chegar um código inválido, é gravado como está)
- Remoção de `taxonomyCode` (limpar um código já definido) — não há botão "desassociar" nesta versão
- Teste de integração Testcontainers para o novo campo no PUT (o service já tem teste unitário)

---

## 7. Testes

### Backend
- `CategoryServiceTest`: `update()` com `taxonomyCode` não nulo e usuário ADMIN → persiste o valor
- `CategoryServiceTest`: `update()` com `taxonomyCode` não nulo e usuário USER → lança `AccessDeniedException`
- `CategoryServiceTest`: `update()` com `taxonomyCode` nulo → não altera o valor existente

### Frontend
- `taxonomy-suggestions.spec.ts`: testes unitários da função `getSuggestion()` com casos PT-BR e sem sugestão
- `TaxonomyMappingComponent`: testes verificando estado após seleção e após conexão (sem TestBed complexo — lógica pura em signals)
