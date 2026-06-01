# Design: Gerenciamento de Transações Parceladas

**Data:** 2026-06-01  
**Status:** Aprovado  
**Escopo:** Backend (Spring Boot) + Frontend (Angular)

---

## Problema

O sistema já suporta criação de transações parceladas (N registros individuais), mas as parcelas são "órfãs" — não há vínculo entre elas. Isso impossibilita:

- Excluir ou editar todas as parcelas de uma compra de uma vez
- Visualizar uma compra parcelada como uma unidade na listagem
- Identificar quais transações pertencem ao mesmo parcelamento

---

## Solução Escolhida

Abordagem **InstallmentGroup como entidade separada**: nova tabela `installment_groups` armazena os metadados originais do grupo. Cada transação parcelada referencia o grupo via FK nullable. Transações avulsas e transferências permanecem com `installment_group_id = null` — zero impacto em dados históricos.

---

## 1. Modelo de Dados

### Nova tabela: `installment_groups`

| Coluna | Tipo | Restrições |
|---|---|---|
| `id` | UUID | PK |
| `description` | VARCHAR | NOT NULL |
| `total_amount` | DECIMAL(15,2) | NOT NULL |
| `total_installments` | INTEGER | NOT NULL |
| `account_id` | UUID | FK → accounts, NOT NULL |
| `category_id` | UUID | FK → categories, nullable |
| `tenant_id` | UUID | FK → tenants, NOT NULL |
| `created_at` | TIMESTAMP | auto |

### Alteração em `transactions`

```sql
ALTER TABLE transactions
  ADD COLUMN installment_group_id UUID REFERENCES installment_groups(id);
```

Transações existentes ficam com `null` — sem necessidade de backfill.

### Índices

```sql
CREATE INDEX idx_transactions_group ON transactions(tenant_id, installment_group_id);
CREATE INDEX idx_installment_groups_tenant ON installment_groups(tenant_id);
```

---

## 2. Backend

### Nova entidade: `InstallmentGroup`

```java
@Entity
@Table(name = "installment_groups")
public class InstallmentGroup {
    UUID id;
    String description;
    BigDecimal totalAmount;
    Integer totalInstallments;
    Account account;
    Category category;   // nullable
    Tenant tenant;
    LocalDateTime createdAt;
}
```

`Transaction` recebe nova associação nullable:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "installment_group_id")
private InstallmentGroup installmentGroup;
```

### Novos endpoints

| Método | Path | Descrição |
|---|---|---|
| `GET` | `/api/installment-groups` | Lista grupos do tenant com progresso agregado |
| `GET` | `/api/installment-groups/{id}` | Detalhe do grupo + parcelas |
| `DELETE` | `/api/installment-groups/{id}` | Exclui grupo (só pendentes; avisa pagas) |
| `PATCH` | `/api/installment-groups/{id}` | Propaga campos selecionados para parcelas pendentes |

### Alterações em endpoints existentes

**`POST /api/transactions`** — quando `totalInstallments > 1`:
1. Cria o `InstallmentGroup` primeiro
2. Cria as N parcelas referenciando o grupo
3. Retorna `InstallmentGroupResponseDTO` (inclui a lista de parcelas)

**`DELETE /api/transactions/{id}`** — novo query param `scope`:

```
?scope=SINGLE        → só esta parcela (padrão atual)
?scope=THIS_AND_NEXT → esta e as com installmentNumber maior
?scope=ALL           → todo o grupo
```

Nos escopos `THIS_AND_NEXT` e `ALL`, parcelas com `status = PAID` são ignoradas. A resposta informa:
```json
{ "deleted": 8, "skippedPaid": 3 }
```

**`PUT /api/transactions/{id}`** — novo campo opcional `propagate: string[]`:

```json
{ "categoryId": "uuid", "propagate": ["categoryId", "description"] }
```

Propagação aplica apenas a parcelas com `status = PENDING` e `installmentNumber > installmentNumber_da_editada`. Campos propagáveis: `description`, `categoryId`, `accountId`, `amount`, `status`. Data e tipo **nunca** propagam.

### Response DTO do grupo

```json
{
  "id": "uuid",
  "description": "Notebook Dell",
  "totalAmount": 6000.00,
  "installmentAmount": 500.00,
  "totalInstallments": 12,
  "paidInstallments": 3,
  "pendingInstallments": 9,
  "nextDueDate": "2026-07-01",
  "categoryName": "Tecnologia",
  "categoryId": "uuid",
  "accountName": "Nubank",
  "accountId": "uuid",
  "transactions": [ /* lista de TransactionResponseDTO */ ]
}
```

---

## 3. Frontend

### Listagem de transações

A lista passa a ser **mista**: transações avulsas/transferências como linhas simples; grupos de parcelamento como linhas colapsáveis.

**Linha colapsada:**
```
▶  Notebook Dell   12x R$ 500,00   [barra progresso 3/12]   Tecnologia   Nubank   [ações]
```
- Clique no `▶` expande as parcelas
- Ações inline: "Excluir grupo" e "Editar grupo" (propagação em massa)

**Sub-linhas expandidas:**
```
↳  1/12   R$ 500,00   01/03/2026   PAGO     [editar] [excluir]
↳  4/12   R$ 500,00   01/06/2026   PENDENTE [editar] [excluir]
```

### Diálogo de exclusão (parcela individual)

```
Excluir parcela — "Notebook Dell" (4/12)

○  Somente esta parcela
○  Esta e as próximas (4ª a 12ª)
○  Todo o grupo (1ª a 12ª)

[Cancelar]   [Confirmar]
```

Se a seleção inclui parcelas pagas, aviso aparece acima do botão:
```
⚠️ 3 parcela(s) já pagas não serão excluídas pois afetariam o histórico
financeiro. Para excluí-las, faça individualmente.
```

### Diálogo de edição com propagação

Ao editar uma parcela de um grupo, seção adicional no final do formulário:

```
Propagar alterações para parcelas futuras pendentes:

☐  Descrição
☐  Categoria
☐  Conta
☐  Valor       ⚠️ apenas parcelas pendentes
☐  Status      ⚠️ apenas parcelas pendentes
```

Todos os checkboxes desmarcados por padrão — propagação é opt-in.

### Formulário de criação — melhorias

**Toggle "É uma compra parcelada?"** — desativado por padrão. Ao ativar, expande seção dedicada:

- Número de parcelas (1–48)
- Modo de valor: "Valor total" ou "Valor da parcela" (radio)
- Data da 1ª parcela (já existe, mas agora com label mais claro)
- **Preview live** das parcelas: tabela que atualiza em tempo real via `computed()` sobre os signals de valor e número de parcelas — sem chamada HTTP

```
Parcela   Data           Valor
1/12      01/06/2026     R$ 500,00
2/12      01/07/2026     R$ 500,00
...
```

Quando desativado, o formulário volta ao modo de transação simples com `totalInstallments = 1`.

---

## 4. Regras de Negócio

| Regra | Detalhe |
|---|---|
| Parcela PAGA nunca é excluída em massa | Sistema ignora e informa quantidade ignorada |
| Status não volta via propagação | `PAID → PENDING` nunca acontece por propagação |
| Data nunca propaga | Cada parcela tem sua própria data por design |
| Tipo nunca propaga | Mudar tipo invalida a semântica do grupo |
| Tenant isolation | Toda query de grupo inclui `tenant_id` — vazamento é bug crítico |
| Grupo órfão | Se todas as parcelas forem excluídas individualmente, o grupo permanece mas fica vazio — limpeza pode ser feita via job futuro ou cascade |

---

## 5. Migration

**V8** — nova migration Flyway:

```sql
CREATE TABLE installment_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    description VARCHAR(255) NOT NULL,
    total_amount DECIMAL(15,2) NOT NULL,
    total_installments INTEGER NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    category_id UUID REFERENCES categories(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    created_at TIMESTAMP DEFAULT now()
);

ALTER TABLE transactions
    ADD COLUMN installment_group_id UUID REFERENCES installment_groups(id);

CREATE INDEX idx_transactions_group ON transactions(tenant_id, installment_group_id);
CREATE INDEX idx_installment_groups_tenant ON installment_groups(tenant_id);
```

---

## 6. O que fica fora deste escopo

- Tela de detalhe dedicada por grupo (listagem completa de parcelas com filtros)
- Reagendamento em massa de datas
- Notificações de parcelas próximas do vencimento
- Exportação de relatórios por grupo
