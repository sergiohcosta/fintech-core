# Spec: Importação Itaú — reconhece parcela 1/N como parcelamento completo

**Data:** 2026-08-06
**Status:** proposto (aguardando aprovação)
**Fonte:** validação manual em dev com fatura real — linhas como "ALLTEC COM DE PECA09/10"
(parcela 9 de 10) hoje viram transação avulsa, perdendo o vínculo de parcelamento que o
sistema já modela nativamente.
**Épico raiz:** #154 — extração multi-mídia e conciliação de transações
Stack: @tech.md · Domínio: @domain.md · Migrations: @database-schema.md

## 1. Contexto

A fatura Itaú imprime cada linha de compra parcelada como `ESTABELECIMENTO NN/MM valor`
(ex.: `09/10` = parcela 9 de 10). O `ItauFaturaTemplate` já reconhece esse padrão hoje, mas
só para REMOVER da descrição (`TRAILING_INSTALLMENT_MARKER`) — o número da parcela e o
total são descartados antes de virar `NormalizedTransactionDTO`. O `ImportService.commit()`
sempre promove staged → 1 `Transaction` avulsa (`TransactionRequestDTO` com
`totalInstallments=null`), mesmo quando a linha de origem é claramente uma parcela de um
parcelamento maior.

O sistema já modela parcelamento nativamente (`InstallmentGroup` + N `Transaction`,
`TransactionService.create` com `totalInstallments>1` — divide `amount` por N, última
parcela absorve o resíduo, `resolveInvoiceMonth` cria as faturas futuras conforme
necessário). O gap é só a importação não estar conectada a esse caminho.

**Fora de escopo, deliberado:** casar a parcela `2/N` que aparecerá na fatura do MÊS
SEGUINTE contra o `InstallmentGroup` já criado a partir da parcela `1/N` deste mês — é o
mesmo problema de matching que o roadmap já reserva para Fase 4 (dedup import×import) e
Fase 5 (conciliação). Construir um casamento ad hoc agora duplicaria esforço sem dado real
pra calibrar. Registrado como dívida técnica conhecida (§7), não resolvido nesta spec.

## 2. Decisões

| # | Decisão | Escolha | Alternativa descartada |
|---|---|---|---|
| a | Onde preservar o metadado de parcela | Novos campos `installment_number`/`installment_total` no `fields` JSONB do `NormalizedTransactionDTO`, populados só pelo `ItauFaturaTemplate` | Campo dedicado fora do JSONB — quebraria o padrão já estabelecido (`fields` é o mapa flexível pra tudo que varia por extrator) |
| b | Valor total do parcelamento (parcela 1/N) | Aproximação: `total = valor_da_parcela × N` | Ler "Compras parceladas - próximas faturas" da mesma fatura pra somar os valores futuros exatos — mais preciso, mas essa seção é hoje um `STOP_MARKER` (fora de escopo do parser); reabri-la é escopo maior que o ganho aqui |
| c | Parcela 1/N no commit | Cria o `InstallmentGroup` completo via `TransactionService.create` com `totalInstallments=N` — reusa 100% da lógica já existente, zero código de domínio novo | Criar uma transação avulsa e um processo separado pra "completar" o grupo depois — mais código, sem necessidade (a lógica de N parcelas já existe e já é chamada por lançamento manual) |
| d | Parcela `>1/N` no commit | Comita como avulsa (comportamento atual, inalterado) — sem tentar reconstruir/achar o grupo | Criar um `InstallmentGroup` parcial começando em N — o domínio não suporta hoje (sempre gera 1..N), estender isso é escopo à parte sem justificativa ainda |
| e | Onde mostrar o aviso de parcela `>1/N` sem grupo conhecido | Frontend, na revisão em lote — badge textual, sem bloquear o commit | Bloquear o commit até o usuário confirmar explicitamente — mais fricção que o valor justifica nesta fatia; usuário já revisa cada linha antes de commitar |

**(a) Campos novos no JSONB, não coluna dedicada.** Mesmo padrão de `currency`,
`posting_date`, `payment_method` — campos que só alguns extratores preenchem. Confiança
`1.0` (veio de um padrão regex casado, não inferência).

**(b) Aproximação por multiplicação.** A fatura já imprime o valor exato da parcela
corrente (`valor_da_parcela`) — multiplicar por N reproduz o `totalAmount` que
`TransactionService.create` vai dividir de volta pela MESMA regra de resíduo (DOWN + última
absorve sobra). Na prática, para N-1 parcelas o valor bate exatamente; a última pode diferir
por centavos do que o Itaú realmente cobrará (ele também pode ajustar por juros/arredondamento
próprios) — aceitável, o usuário revisa antes de commitar e pode corrigir via `PATCH` staged
antes do commit, caminho que já existe.

**(c) Reusa `TransactionService.create` sem mudança nele.** `ImportService.commit()` passa a
montar `TransactionRequestDTO` com `totalInstallments = installment_total` e
`amount = valor_da_parcela × installment_total` quando `installment_number == 1`. O serviço
de transação não muda uma linha — ele já sabe dividir, criar `InstallmentGroup`, resolver
fatura por `resolveInvoiceMonth`. Isso só é seguro porque a conta de origem de uma fatura
Itaú é sempre `CREDIT_CARD` (requisito já existente do parcelamento nativo) — importação de
fatura nunca aponta pra outro tipo de conta.

**(d) Parcela `>1/N` fica avulsa.** Sem o valor total real da compra (só temos a parcela
atual) nem histórico das parcelas anteriores no sistema, criar qualquer coisa automática
seria inventar dado. Avulsa + aviso é o caminho "erro explícito, nunca dado errado
silencioso" que o projeto já segue em toda a Fase de extração.

## 3. Modelo de dados / Contrato de API

Nenhuma migration, nenhuma coluna nova — `fields` já é JSONB flexível. Nenhuma mudança de
contrato REST (`installment_number`/`installment_total` trafegam dentro do `fields` já
existente na resposta de `GET /staged`, que o frontend já lê genericamente).

## 4. Fluxo

### 4.1 `ItauFaturaTemplate.parseLinha` — preserva o metadado

Hoje `TRAILING_INSTALLMENT_MARKER` só remove o sufixo. Passa a capturar o grupo antes de
remover:

```
"09/10" no fim do texto antes do valor →
  installment_number = 9
  installment_total  = 10
```

Ausência do marcador (linha sem parcela) → campos ausentes no `fields` (mesmo padrão de
`currency` ausente hoje). `toDto` grava:
```java
if (t.installmentNumber() != null) {
    fields.put("installment_number", new StagedFieldValueDTO(t.installmentNumber(), BigDecimal.ONE));
    fields.put("installment_total", new StagedFieldValueDTO(t.installmentTotal(), BigDecimal.ONE));
}
```

### 4.2 `ImportService.commit()` — rota de parcela 1

Dentro do loop de `commit()`, antes de montar `TransactionRequestDTO`:

```
installmentNumber = fieldValue(staged, "installment_number", ...)
installmentTotal   = fieldValue(staged, "installment_total", ...)

se installmentNumber == 1 E installmentTotal != null E installmentTotal > 1:
    totalAmount = amount × installmentTotal   // amount = valor da parcela já lido
    TransactionRequestDTO(..., amount=totalAmount, totalInstallments=installmentTotal, ...)
senão:
    TransactionRequestDTO(..., amount=amount, totalInstallments=null, ...)  // inalterado
```

`transactionService.create(...)` já devolve a LISTA de N transações criadas — `commit()`
hoje só usa `created.get(0)` (a primeira) pra gravar `promotedTransactionId` na staged.
Mantido: a staged sempre representa a linha da fatura (parcela 1), que corresponde à
PRIMEIRA transação do grupo criado.

### 4.3 Frontend — agrupamento visual na revisão em lote

Tabela de staged (já existente, Fase 2 metade B) ganha particionamento em 2 seções:
**Avulsas** e **Parceladas**. Critério: presença de `installment_number`/`installment_total`
no `fields` da staged.

Dentro de "Parceladas", por linha:
- `installment_number == 1`: texto "Vai criar parcelamento completo ({N} parcelas)".
- `installment_number > 1`: badge de aviso "Parcela {NN} de {N} — parcelas anteriores não
  importadas, confira antes de lançar" (cor de atenção, mesmo padrão visual do badge
  "Possível duplicata" já existente).

Sem mudança de endpoint — o particionamento e os textos são lógica de apresentação sobre o
`GET /staged` já existente.

## 5. Testes

| Camada | Cobertura |
|---|---|
| `ItauFaturaTemplateTest` | linha com marcador de parcela grava `installment_number`/`installment_total` corretos; linha sem marcador não grava os campos (ausentes, não `null` explícito nem zero) |
| `ImportServiceTest` (ou equivalente) | commit de staged com `installment_number=1` cria N transações via `TransactionService.create` com `totalInstallments` correto e `amount` = parcela×N; commit de staged com `installment_number=3` (sem ser 1) cria transação avulsa, comportamento inalterado; commit de staged sem os campos (Nubank/genérico) inalterado |
| Frontend (Vitest, lógica pura) | função de particionamento avulsa/parcelada classifica corretamente uma lista mista de staged; textos de badge corretos por caso (`1/N`, `>1/N`, sem parcela) |

Fixtures sintéticas — mesmo padrão já usado (nomes/valores fictícios).

## 6. Impacto SemVer

**PATCH** — nenhuma mudança de contrato REST (campos novos dentro de um JSONB já
genericamente exposto), nenhuma migration.

## 7. Dívida técnica registrada

Casar parcela `>1/N` contra um `InstallmentGroup` já existente no sistema (criado por uma
importação de mês anterior, ou por lançamento manual) é reconciliação de verdade — mesmo
motor de matching (peso por valor + data + descrição) que a Fase 4/5 do roadmap de extração
já reserva. Quando esse motor existir, a UI de importação pode oferecer "vincular à parcela
X do grupo Y" em vez de só avisar. Até lá, o aviso na revisão é a mitigação — usuário decide
manualmente se já lançou as parcelas anteriores por outro caminho.
