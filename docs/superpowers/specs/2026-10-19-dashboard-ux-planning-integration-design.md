# Spec de Design: Dashboard Integrado ao Planejamento Ativo (Budget Cycle)

**Data:** 19 de Outubro de 2026  
**Status:** Em Implementação  
**Autor:** Antigravity (Arquiteto & Executor)  
**Contexto:** Reestruturação do Dashboard para refletir o Planejamento Ativo Corrente, Posição Hoje e Atividades Recentes.

---

## 1. Contexto & Diagnóstico

### 1.1. Ambiguidade Temporal do Dashboard Anterior
* No modelo anterior, o Dashboard exibia um seletor mensal no cabeçalho ("Outubro de 2026"), mas agregava dados de três naturezas temporais distintas sem identificação visual clara:
  1. **Posição Financeira Líquida**: acumulado imediato de hoje (saldo em contas ativas líquidas).
  2. **Receita / Despesa / Saldo**: fixado no mês calendário (01/10 a 31/10).
  3. **Transações Recentes**: os 5 lançamentos mais recentes de todo o histórico, ignorando o mês selecionado.
* Usuários reais frequentemente orçam em ciclos alinhados ao recebimento salarial (ex.: dia 05 a dia 04 do mês seguinte). O sistema já possui a entidade `BudgetCycle`, cujo intervalo personalizado reflete a realidade do usuário.

### 1.2. Solução Proposta: 3 Blocos de Alto Valor
1. **Bloco 1: "Minha Situação Hoje"** *(Instantâneo / Caixa Imediato)*
   - Saldo líquido total consolidado em contas ativas.
   - Pílulas/carrossel com contas bancárias, cartões e carteira de dinheiro.
   - Botão de privacidade com um toque (`hideValues`).
   - Ações rápidas: Nova Transação, Transferir, Importar Extrato.
2. **Bloco 2: "Planejamento Ativo"** *(Competência / Ciclo Corrente)*
   - Integrado diretamente ao `PlanningService.getCurrentCycle()`.
   - Se houver ciclo ativo (`status === 'OPEN'`):
     - Rótulo claro com o intervalo: `startDate | date:'dd/MM'` a `endDate | date:'dd/MM/yyyy'`.
     - Badges: `Aberto` e `X dias restantes`.
     - Indicador central: **Disponível para Gastar** (`availableToSpend`) e **Limite Diário** (`dailyAllowance`).
     - Metas: **Receitas** (Previsto vs. Realizado) e **Despesas** (Previsto vs. Realizado).
     - Atalho para a tela de planejamento (`/planning`).
   - Se NÃO houver ciclo ativo:
     - Card convidativo explicando a ausência de ciclo aberto.
     - Botão CTA: "Abrir Planejamento" (`/planning`).
     - Resumo secundário do mês calendário para não deixar o usuário sem visão geral.
3. **Bloco 3: "Últimas Movimentações"** *(Atividade Recente)*
   - Lista das 5 transações mais recentes com data `dd/MM`, conta, categoria e valor formatado.
   - Link direto para o extrato completo (`/transactions`).

---

## 2. Implementação Técnica

### 2.1. Frontend (`dashboard.ts`, `dashboard.html`, `dashboard.scss`)
* Injetar `PlanningService`.
* Escutar `transactionEvents.refreshTrigger` para recarregar o ciclo ativo `planningService.getCurrentCycle()`.
* Mapear signals e computeds para `currentCycle`, `cycleSummary`, `hasActiveCycle`, `availableToSpend`, `dailyAllowance`, `remainingDays`.
* Atualizar template HTML dividindo explicitamente em seções nomeadas.
* Garantir formatação segura no modo privacidade (`formatMoney`).

### 2.2. Testes & Validação
* Atualizar `dashboard.spec.ts` cobrindo cenários com ciclo ativo e sem ciclo ativo.
* Suíte Vitest completa e build de produção green.
