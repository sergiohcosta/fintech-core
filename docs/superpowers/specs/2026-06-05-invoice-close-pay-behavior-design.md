# Design: Comportamento de Fechar e Pagar Fatura

**Data:** 2026-06-05  
**Status:** Aprovado

---

## Contexto

As ações `POST /invoices/{id}/close` e `POST /invoices/{id}/pay` existem desde a issue #42, mas hoje apenas mudam o status da fatura (`OPEN → CLOSED → PAID`) sem nenhum efeito colateral. Isso deixa dois buracos:

1. As transações de cartão permanecem `PENDING` mesmo depois que a fatura é paga — o status da transação não reflete a realidade financeira.
2. O saldo líquido nunca é impactado pelos gastos do cartão, porque `CREDIT_CARD` tem `countInLiquidBalance = false` e nenhuma saída de caixa é registrada no momento do pagamento.

Este spec define o comportamento completo de fechar e pagar uma fatura.

---

## Decisões de Design

### Fechar (`OPEN → CLOSED`)

- Nenhum efeito colateral nas transações.
- A fatura **não** trava: novas transações do período ainda podem ser associadas a ela após o fechamento. Fechar é um marcador administrativo, não um bloqueio técnico.
- O frontend exibe aviso visual (chip/badge) quando a fatura está fechada, para comunicar o estado sem restringir operações.

**Motivação:** rigidez no fechamento criaria fricção desnecessária para ajustes legítimos (ex: chegou uma cobrança atrasada). O aviso visual cumpre o papel informativo sem bloquear.

---

### Pagar (`CLOSED → PAID`)

O pagamento é o ponto de não-retorno. Dispara três efeitos dentro de uma única `@Transactional`:

1. **Marca transações `PENDING` → `PAID`** via `@Modifying` query em batch (sem loop N+1).
2. **Cria uma `EXPENSE` na conta de origem** para registrar a saída de caixa, SE o total da fatura for > 0.
3. **Muda o status da fatura para `PAID`**.

**Por que registrar a saída de caixa?**
`CREDIT_CARD` tem `countInLiquidBalance = false`. As compras não impactam o saldo líquido no momento da compra — o impacto real ocorre quando o dinheiro sai da conta corrente para pagar o cartão. Sem a transação de débito, os gastos do cartão nunca aparecem na posição financeira.

---

## Contrato da API

### `POST /invoices/{id}/close`
Sem alterações no contrato. Resposta: `InvoiceResponseDTO` com `status: CLOSED`.

### `POST /invoices/{id}/pay`

**Request body (novo):**
```json
{ "sourceAccountId": "uuid" }
```

**Validações:**
- `sourceAccountId` deve pertencer ao tenant autenticado → 404 se não encontrar.
- Tipo da conta de origem não pode ser `CREDIT_CARD` → 422: `"Não é possível pagar uma fatura com outra conta de crédito."`
- Status da fatura deve ser `CLOSED` → 422: `"Só é possível pagar faturas com status CLOSED."`

**Resposta:** `InvoiceResponseDTO` com `status: PAID`.

---

## Backend — Mudanças

### `InvoicePayDTO` (novo)
```java
public record InvoicePayDTO(
    @NotNull UUID sourceAccountId
) {}
```

### `TransactionRepository` — nova query
```java
@Modifying
@Query("""
    UPDATE Transaction t
       SET t.status = :newStatus
     WHERE t.invoice = :invoice
       AND t.status = :currentStatus
    """)
int updateStatusByInvoiceAndStatus(
    @Param("invoice") Invoice invoice,
    @Param("currentStatus") TransactionStatus currentStatus,
    @Param("newStatus") TransactionStatus newStatus
);
```

### `InvoiceService.pay()` — fluxo completo
```
1. findByIdAndTenant(id, tenant)  →  valida existência e ownership
2. Valida status == CLOSED
3. findByIdAndTenant(sourceAccountId, tenant)  →  valida conta
4. Valida account.type != CREDIT_CARD
5. total = sumAmountByInvoice(invoice, CANCELLED)
6. SE total > 0:
     cria Transaction {
       type:        EXPENSE
       status:      PAID
       amount:      total
       date:        LocalDate.now()   ← data real do pagamento, não dueDate
       description: "Pagamento fatura {account.name} {referenceMonth}/{referenceYear}"
                  ← MM e yyyy referem-se à referência da fatura (ex: "06/2026"), não à data de pagamento
       account:     sourceAccount
       tenant:      invoice.tenant
       user:        usuário autenticado
       invoice:     null
       installmentGroup: null
     }
7. updateStatusByInvoiceAndStatus(invoice, PENDING, PAID)
8. invoice.setStatus(PAID)
9. repository.save(invoice)
10. log.info(...)
```

**Nota sobre data:** usa `LocalDate.now()` e não `invoice.dueDate`. O vencimento pode já ter passado; o registro deve refletir quando o pagamento ocorreu de fato.

**Nota sobre total zero:** se todas as transações da fatura foram canceladas, o total é 0 e nenhum débito é criado. A fatura ainda muda para `PAID`.

### `InvoiceController`
```java
@PostMapping("/{id}/pay")
public ResponseEntity<InvoiceResponseDTO> payInvoice(
        @PathVariable UUID id,
        @Valid @RequestBody InvoicePayDTO dto) {
    User user = getAuthenticatedUser();
    return ResponseEntity.ok(
        invoiceService.pay(id, user.getTenant(), user, dto.sourceAccountId())
    );
}
```

### OpenAPI (`openapi.yaml`)
O endpoint `POST /invoices/{id}/pay` precisa declarar o request body com `InvoicePayDTO`. A atualização do spec propaga o tipo TypeScript para o frontend via Orval.

---

## Frontend — Mudanças

### Aviso de fatura fechada (`InvoiceListComponent`)
- Chip/badge visual na linha quando `status === 'CLOSED'` ou `status === 'PAID'`.
- Botão "Fechar" visível apenas quando `status === 'OPEN'`.
- Botão "Pagar" visível apenas quando `status === 'CLOSED'`.

### Diálogo de pagamento (`InvoicePayDialog`)
Abre ao clicar em "Pagar fatura". Contém:
- Exibição do total da fatura (somente leitura).
- `mat-select` com contas ativas do tenant, excluindo `CREDIT_CARD` (filtro no componente, sem chamada extra à API).
- Botões "Cancelar" e "Confirmar pagamento".

Ao confirmar, chama `POST /invoices/{id}/pay` com `{ sourceAccountId }`. Em sucesso, recarrega a lista de faturas.

**Edge case — sem contas elegíveis:** se o tenant não tiver nenhuma conta ativa que não seja `CREDIT_CARD`, o select fica vazio e o botão "Confirmar" deve ser desabilitado, com mensagem: `"Nenhuma conta disponível para pagamento. Cadastre uma conta corrente ou carteira."`. O usuário precisa criar uma conta antes de pagar a fatura.

---

## Fora do Escopo

- Desfazer pagamento (estorno de fatura) — não previsto.
- Pagamento parcial de fatura — não previsto.
- Notificações ou alertas de vencimento — não previsto.

---

## Ciclo de vida completo (referência)

```
OPEN   → [fechar]  → CLOSED  (sem efeito nas transações; novas entradas permitidas com aviso)
CLOSED → [pagar]   → PAID    (transações PENDING → PAID; débito criado na conta de origem)
```
