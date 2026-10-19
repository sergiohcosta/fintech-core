# Spec de Design: Correção de Deduplicação de Transações Fantasmas (Issue #142)

**Issue:** #142 — `bug(transacoes): fantasmas (id=null) deduplicados somem da lista`  
**Data:** 19 de Outubro de 2026  
**Status:** Pronto para Implementação  
**Autor:** Antigravity (Arquiteto)  
**Executor:** Claude Code CLI (Worktree isolada, Sonnet sem `claude-mem`)

---

## 1. Contexto & Diagnóstico da Falha

No frontend, quando o usuário visualiza transações com projeções futuras ativas (`includeProjected: true`), as regras de recorrência geram linhas "fantasmas" (`projected: true`, com `id: null` no contrato de dados).

### Onde ocorre o bug:
Em `frontend/src/app/features/transaction/transaction-list/transaction-list.utils.ts`:
```typescript
function sortTransferPairsTogether(transactions: TransactionResponseDTO[]): TransactionResponseDTO[] {
  const result: TransactionResponseDTO[] = [];
  const placed = new Set<string>();
  for (const t of transactions) {
    if (placed.has(t.id)) continue;
    placed.add(t.id);
    result.push(t);
    if (t.transferId) {
      const pair = transactions.find(
        other => other.transferId === t.transferId && other.id !== t.id && !placed.has(other.id)
      );
      if (pair) {
        placed.add(pair.id);
        result.push(pair);
      }
    }
  }
  return result;
}
```

### O Problema:
1. O conjunto `placed = new Set<string>()` serve unicamente para evitar que a segunda perna de uma transferência pareada seja duplicada ao ser encontrada pelo loop.
2. Quando transações fantasmas (`id: null`) passam pelo loop:
   - A 1ª transação fantasma adiciona `null` ao `placed` (`placed.add(null)`).
   - Para qualquer 2ª, 3ª ou N-ésima transação fantasma seguinte, `placed.has(null)` retorna `true`!
   - Como consequência, a linha executa `continue;` e todas as transações fantasmas subsequentes do mês são **silenciosamente descartadas** da lista!
3. Além disso, no template `transaction-list.html` (linha 225), a expressão de track do `@for`:
   `track row.kind === 'period-header' ? row.key : row.kind === 'invoice-summary' ? row.invoiceId : row.kind + row.data.id`
   avalia para `'singlenull'` para todas as linhas fantasmas, gerando colisões de chaves no diffing de DOM do Angular.

---

## 2. Solução Técnica

### 2.1. Ajuste em `sortTransferPairsTogether`
Linhas sem `id` (ou identificadas como fantasmas) não formam pares de transferência e **nunca** devem ser bloqueadas pelo `placed`:

```typescript
function sortTransferPairsTogether(transactions: TransactionResponseDTO[]): TransactionResponseDTO[] {
  const result: TransactionResponseDTO[] = [];
  const placed = new Set<string>();
  for (const t of transactions) {
    if (t.id && placed.has(t.id)) continue;
    if (t.id) placed.add(t.id);
    result.push(t);
    if (t.transferId) {
      const pair = transactions.find(
        other => other.transferId === t.transferId && other.id !== t.id && (!other.id || !placed.has(other.id))
      );
      if (pair) {
        if (pair.id) placed.add(pair.id);
        result.push(pair);
      }
    }
  }
  return result;
}
```

### 2.2. Defensiva em `buildFlatRows`
Garantir que checagem de expansão não faça lookups inválidos:
```typescript
const isExpanded = t.id ? expandedIds.has(t.id) : false;
```

### 2.3. Chave de Rastreamento Única (`rowTrackKey`)
Exportar em `transaction-list.utils.ts` uma função de tracking estável:
```typescript
export function rowTrackKey(row: DisplayRow, index: number): string {
  if (row.kind === 'period-header') return row.key;
  if (row.kind === 'invoice-summary') return row.invoiceId;
  if (row.data.id) return `${row.kind}:${row.data.id}`;
  if (isGhost(row.data)) {
    return `ghost:${row.data.recurrenceRuleId ?? ''}:${row.data.occurrenceDate ?? row.data.date ?? index}`;
  }
  return `${row.kind}:${index}`;
}
```

E no `transaction-list.ts`:
- Importar e expor `readonly rowTrackKey = rowTrackKey;`
- No `transaction-list.html`:
  Atualizar o `@for`:
  ```html
  @for (row of displayRows(); track rowTrackKey(row, $index)) {
  ```

---

## 3. Plano de Testes Unitários

Em `transaction-list.utils.spec.ts`:
1. **Preservação de Múltiplos Fantasmas:**
   Criar teste provando que `buildDisplayRows` recebe 2 ou mais transações com `id: null` / `projected: true` e preserva todas no resultado (nenhuma é descartada por deduplicação).
2. **Unicidade de `rowTrackKey`:**
   Testar que duas transações fantasmas diferentes geram chaves de rastreamento distintas para o `@for`.
3. **Pares de Transferência Intactos:**
   Garantir que a deduplicação de pares normais de transferência continua funcionando perfeitamente sem duplicações.
