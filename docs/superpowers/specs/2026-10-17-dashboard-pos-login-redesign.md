# Especificação: Redesign do Dashboard Pós-Login (Desktop & Mobile)

**Data:** 2026-10-17  
**Status:** Aprovado para Implementação  
**Autor:** Antigravity (Arquiteto de Solução) & Claude Code (Executor)  
**Escopo:** Frontend Angular 21 (Zoneless Signals + Material 3)  
**Branch Alvo:** `develop` (via worktree `.worktrees/dashboard-pos-login`)

---

## 1. Contexto & Diagnóstico da UX Atual

### 1.1 O Problema
Atualmente, a tela de `/dashboard` apresenta limitações significativas tanto para novos usuários quanto para usuários recorrentes:

1. **O "Abismo do Dia Zero" (Cold Start):**
   - Quando um novo usuário/tenant se cadastra, ele possui **0 contas** e **0 transações**.
   - O dashboard atual exibe apenas um card estático de "Posição atual: R$ 0,00" e uma mensagem "Nenhuma movimentação em Outubro de 2026" com o botão "+ Lançar transação".
   - Se o usuário clica em lançar transação, ele é bloqueado porque não possui nenhuma conta bancária cadastrada para associar ao lançamento. Não há boas-vindas nem trilha de primeiros passos.
2. **Falta de Visibilidade das Contas (Dia 1+):**
   - O usuário só vê um totalizador líquido consolidado. Ele não enxerga a distribuição dos seus saldos (ex.: "Nubank: R$ 1.500", "Itaú: R$ 3.200", "Carteira: R$ 120") sem ter que navegar até a tela de Contas.
3. **Ausência de Ações Rápidas no Desktop:**
   - No desktop, para importar um extrato bancário (OFX/PDF) ou realizar uma transferência, o usuário precisa caçar os itens no menu lateral.
4. **Falta de Modo Privacidade:**
   - Em aplicativos de gestão financeira, usuários frequentemente acessam a tela inicial em ambientes públicos (escritório, transporte público, cafeteria) ou compartilham a tela. Não existe opção para ofuscar os saldos monetários (`R$ •••••`).
5. **Aproveitamento de Espaço Desbalanceado:**
   - **No Desktop:** cards ocupam espaço horizontal de forma dispersa, deixando áreas vazias e listas com pouca densidade de informação.
   - **No Mobile:** a rolagem vertical rápida perde visibilidade das contas ativas e os cards de receita/despesa empilhados tomam a primeira dobra inteira.

---

## 2. Objetivos de Design & Princípios

- **Amigável e Acolhedor:** Linguagem humana, com saudação contextual ("Bom dia, [Nome]"), orientações claras e sem jargões contábeis agressivos.
- **Orientado à Ação (Action-Driven):** O usuário deve saber exatamente o que fazer no Dia 0, e conseguir lançar, transferir ou importar extrato em 1 clique/toque no Dia 1+.
- **Privacidade com 1 Toque:** Alternância imediata entre visualização de valores reais e valores mascarados (`R$ •••••`), com persistência local.
- **Responsividade Especializada (Adaptive):**
  - **Desktop (>= 1024px):** Layout em grade de 2 colunas: Coluna Principal (Métricas do Mês, Balanço e Transações Recentes detalhadas) + Barra Lateral (Minhas Contas com saldos e atalhos de Ações Rápidas).
  - **Mobile (< 720px):** Cabeçalho compacto, saldo líquido em destaque com alternador de privacidade, carrossel horizontal de mini-cards de contas, métricas resumidas do mês e lista de últimas transações com atalhos de toque.
- **Zero Backend Breaking Change:** Composição reativa no Angular 21 utilizando as APIs já existentes (`DashboardService.getDashboardSummary`, `AccountsService.listAccounts`, `TransactionsService.listTransactions`).

---

## 3. Arquitetura de Interface & Wireframes

### 3.1 Desktop Layout (`expanded` >= 1024px)

```text
+----------------------------------------------------------------------------------------------------+
| Nosso Dinheirinho                                                          [Olá, Sérgio] [👁] [Sair]|
+----------------------------------------------------------------------------------------------------+
| [Menu]  Dashboard                                                   [ < Outubro de 2026 > ]         |
|                                                                                                    |
|  +---------------------------------------------------------+  +---------------------------------+  |
|  | COLUNA PRINCIPAL (70%)                                  |  | BARRA LATERAL (30%)             |  |
|  |                                                         |  |                                 |  |
|  | [Card de Boas-vindas / Primeiros Passos] (se 0 contas)  |  | [ AÇÕES RÁPIDAS ]               |  |
|  |                                                         |  | [+ Nova Transação]              |  |
|  | [ Posição Financeira Líquida: R$ 14.850,00        [👁] ]|  | [⇄ Transferência]               |  |
|  |                                                         |  | [↑ Importar Extrato (OFX/PDF)]  |  |
|  | [ Cards do Mês: 3 Colunas ]                             |  |                                 |  |
|  | +------------------+ +-----------------+ +------------+ |  | [ MINHAS CONTAS (3) ]   [+ Nova]|  |
|  | | ↑ Receitas       | | ↓ Despesas      | | = Saldo    | |  | +-----------------------------+ |  |
|  | | R$ 8.500,00      | | R$ 3.200,00     | | R$ 5.300,00| |  | | Nubank         R$  1.500,00 | |  |
|  | +------------------+ +-----------------+ +------------+ |  | | Itaú Corrente  R$  3.350,00 | |  |
|  |                                                         |  | | Reserva CDI    R$ 10.000,00 | |  |
|  | [ Barra de Progresso / Economia: "62% poupado do mês" ] |  | +-----------------------------+ |  |
|  |                                                         |  |                                 |  |
|  | [ TRANSAÇÕES RECENTES ]                       [Ver tudo]|  | [ DICA / PLANEJAMENTO ]         |  |
|  | +-----------------------------------------------------+ |  | Orçamento do mês: 40% usado    |  |
|  | | 🛒 Supermercado Zona Sul   16/10   -R$ 342,10 [Alim]| |  | [Ver Planejamento →]           |  |
|  | | 💼 Salário Fintech         05/10 +R$ 8.500,00 [Rend]| |  +---------------------------------+  |
|  | | ⚡ Conta de Luz            03/10   -R$ 180,00 [Mora]| |                                     |
|  | +-----------------------------------------------------+ |                                     |
|  +---------------------------------------------------------+                                     |
+----------------------------------------------------------------------------------------------------+
```

### 3.2 Mobile Layout (`compact` < 720px)

```text
+------------------------------------------+
| ≡  Nosso Dinheirinho            [👁] [Sair]|
+------------------------------------------+
| Olá, Sérgio 👋                            |
| [< Outubro de 2026 >]                    |
|                                          |
| +--------------------------------------+ |
| | Posição Líquida                      | |
| | R$ 14.850,00                     [👁] | |
| +--------------------------------------+ |
|                                          |
| CONTAS                                   |
| [ Nubank R$ 1.5k ] [ Itaú R$ 3.3k ] [ + ]|  <-- Carrossel horizontal deslizável
|                                          |
| RESUMO DE OUTUBRO                        |
| +--------------------------------------+ |
| | Receitas: R$ 8.500   Despesas: 3.200 | |
| | Saldo do Período:    +R$ 5.300,00    | |
| +--------------------------------------+ |
|                                          |
| ATALHOS RÁPIDOS                          |
| [ ↑ Importar Extrato ] [ ⇄ Transferir ]  |
|                                          |
| TRANSAÇÕES RECENTES            [Ver tudo]|
| +--------------------------------------+ |
| | 🛒 Supermercado            -R$ 342,10| |
| | 💼 Salário               +R$ 8.500,00| |
| | ⚡ Luz                     -R$ 180,00| |
| +--------------------------------------+ |
|                                          |
| [ Início ] [ Transações ] [ + ] [ Plan ] |  <-- Bottom Tab Bar (fixa)
+------------------------------------------+
```

---

## 4. Detalhamento dos Estados da Interface

### Estado 0: "Dia Zero" (Onboarding / Sem Contas Cadastradas)
- **Gatilho:** `accounts().length === 0`.
- **Apresentação:**
  - Card Hero de Boas-Vindas estilizado com `mat-sys-primary-container`.
  - Mensagem: *"Boas-vindas ao Nosso Dinheirinho! Vamos organizar sua vida financeira juntos."*
  - Stepper ou Checklist visual claro:
    1. **1. Crie sua primeira conta ou carteira:** (ex.: Nubank, Itaú, Carteira em Dinheiro).
       - Botão de Ação Primária: `[+ Criar Primeira Conta]` (navega para `/accounts` ou abre modal de criação).
    2. **2. Traga seus dados:**
       - Texto: *"Após criar a conta, você pode importar um extrato bancário (OFX/PDF) ou lançar seu saldo inicial."*
       - Botão Secundário: `[Importar Extrato]` (habilitado após a criação da conta).
    3. **3. Convide seu parceiro(a):**
       - Texto: *"Compartilhe a gestão financeira do seu lar convidando membros da família."*
       - Link discreto: *"Gerenciar membros"*.

### Estado 1: Mês Sem Movimentações (Contas Criadas, mas Período Zerado)
- **Gatilho:** `accounts().length > 0` e `summary()?.transactionCount === 0`.
- **Apresentação:**
  - Posição financeira e lista de contas continuam visíveis normalmente (pois refletem a saúde patrimonial real).
  - No bloco de resumo mensal: banner sutil *"Nenhuma movimentação registrada em [Mês]".*
  - CTAs contextuais:
    - `[+ Novo Lançamento]` (abre `TransactionQuickSheetComponent` no mobile ou navega para `/transactions` no desktop).
    - `[↑ Importar Extrato]` (abre `/import`).

### Estado 2: Dashboard Completo e Ativo
- **Gatilho:** `accounts().length > 0` e `summary()?.transactionCount > 0`.
- Exibição de todos os componentes com métricas reais, indicadores coloridos de superávit/déficit e transações recentes.

---

## 5. Especificação Funcional dos Componentes

### 5.1 Modo Privacidade (Eye Toggle)
- **Comportamento:**
  - Botão de ícone no cabeçalho do Dashboard (`visibility` / `visibility_off`).
  - Ao clicar, alterna o signal `hideValues: WritableSignal<boolean>`.
  - Persistido no `localStorage.getItem('fintech_hide_values')`.
  - Quando `true`:
    - Todos os valores monetários no Dashboard exibem `R$ •••••`.
    - Tooltips de valores também são mascarados.
    - O ícone muda para `visibility_off` com tooltip "Exibir valores".

### 5.2 Carrossel / Lista de Minhas Contas
- **Fonte de dados:** `AccountsService.listAccounts()`.
- **Mobile (`compact`):**
  - Carrossel horizontal com `display: flex; overflow-x: auto; scroll-snap-type: x mandatory; gap: 8px;`.
  - Mini-cards contendo:
    - Ícone da conta ou tipo (ex.: `account_balance`, `credit_card`, `payments`).
    - Nome da conta truncado (ex.: "Nubank").
    - Saldo atual formatado (ou mascarado se `hideValues`).
    - Mini-card final "+ Nova Conta" redirecionando para `/accounts`.
- **Desktop (`expanded`):**
  - Lista vertical em card lateral com `mat-list` estilizado.
  - Exibe tipo de conta, nome, saldo alinhado à direita e link direto para detalhes.

### 5.3 Ações Rápidas (Quick Actions)
- **Desktop:** Card dedicado no topo da coluna lateral com 3 botões rápidos com ícones:
  - `[ + Nova Transação ]` (Stroked/Filled button com ícone `add`)
  - `[ ⇄ Nova Transferência ]` (Stroked button com ícone `sync_alt`)
  - `[ ↑ Importar Extrato ]` (Stroked button com ícone `upload_file`)
- **Mobile:** Dois chips/botões horizontais compactos para `Importar Extrato` e `Transferir` (o botão de lançamento rápido "+" já reside permanentemente na barra inferior).

### 5.4 Resumo do Mês & Indicador de Saúde
- **Cards:**
  - **Receitas:** Verde (`#2e7d32`), ícone `trending_up`.
  - **Despesas:** Vermelho (`#c62828`), ícone `trending_down`.
  - **Saldo do Período:** Azul se positivo (`#1565c0`), Vermelho se negativo (`#c62828`), ícone `account_balance_wallet`.
- **Indicador de Economia / Taxa de Poupança (Simples):**
  - Se `totalIncome > 0` e `totalExpense >= 0`:
    - Taxa de economia = `((totalIncome - totalExpense) / totalIncome) * 100`.
    - Barra de progresso ou badge:
      - Se economia > 0: *"🎉 Você poupou X% da sua receita este mês"*.
      - Se despesa > receita: *"⚠️ Despesas superaram receitas em R$ Y"*.

### 5.5 Transações Recentes
- Exibe até 5 transações mais recentes obtidas via `TransactionsService.listTransactions()`.
- Cada item exibe:
  - Ícone da categoria com cor de destaque (fallback para ícone padrão por tipo).
  - Descrição da transação e nome da conta de origem.
  - Data no formato `dd/MM` ou `Hoje / Ontem`.
  - Valor com cor indicativa (`+` verde para receita, `-` vermelho para despesa).
  - Chip ou badge sutil com a categoria.

---

## 6. Arquitetura Técnica & Implementação no Frontend

### 6.1 Padrão Angular 21 (Strict Zoneless Signals)
- Proibido uso de `Zone.js` ou variáveis mutáveis fora de signals.
- Injeção de dependências via `inject()`.
- Reatividade com `computed()`, `signal()` e `toSignal(toObservable(...))` para streams.

### 6.2 Estrutura de Arquivos

```text
frontend/src/app/features/dashboard/
├── dashboard.ts          # Lógica do componente, signals, serviços
├── dashboard.html        # Template adaptativo com @if, @for
├── dashboard.scss        # Estilos com mixins de breakpoint e tokens CSS
└── dashboard.spec.ts     # Testes unitários com Vitest
```

### 6.3 State & Signals do `DashboardComponent`

```typescript
export class DashboardComponent {
  private dashboardService = inject(DashboardService);
  private transactionService = inject(TransactionsService);
  private accountsService = inject(AccountsService);
  private transactionEvents = inject(TransactionEventsService);

  // Controle de Mês
  private now = new Date();
  selectedYear = signal(this.now.getFullYear());
  selectedMonthIndex = signal(this.now.getMonth() + 1);

  // Modo Privacidade
  hideValues = signal<boolean>(localStorage.getItem('fintech_hide_values') === 'true');

  togglePrivacy(): void {
    this.hideValues.update(v => {
      const next = !v;
      localStorage.setItem('fintech_hide_values', String(next));
      return next;
    });
  }

  // Carregamento de Contas
  private accounts$ = toObservable(this.transactionEvents.refreshTrigger).pipe(
    switchMap(() => this.accountsService.listAccounts())
  );
  accounts = toSignal(this.accounts$, { initialValue: [] });
  hasAccounts = computed(() => this.accounts().length > 0);

  // Carregamento de Resumo Financeiro
  private refreshState = computed(() => ({
    refresh: this.transactionEvents.refreshTrigger(),
    month: this.selectedMonth(),
  }));
  private summary$ = toObservable(this.refreshState).pipe(
    switchMap(({ month }) => this.dashboardService.getDashboardSummary({ month }))
  );
  summary = toSignal(this.summary$, { initialValue: null });
  hasTransactions = computed(() => (this.summary()?.transactionCount ?? 0) > 0);

  // Transações Recentes
  private recentTransactions$ = toObservable(this.transactionEvents.refreshTrigger).pipe(
    switchMap(() => this.transactionService.listTransactions())
  );
  recentTransactions = toSignal(this.recentTransactions$, { initialValue: [] });

  // Métricas Derivadas
  savingsRate = computed(() => {
    const s = this.summary();
    if (!s || !s.totalIncome || s.totalIncome <= 0) return 0;
    const balance = s.balance ?? (s.totalIncome - (s.totalExpense ?? 0));
    return Math.round((balance / s.totalIncome) * 100);
  });
}
```

---

## 7. Responsividade & Design Tokens

Utilização estrita dos tokens semânticos de `frontend/src/styles/_tokens.scss` e mixins de `frontend/src/styles/_breakpoints.scss`:

- **Touch Target Mínimo:** `var(--touch-target-min, 48px)` em todos os botões e áreas clicáveis no mobile.
- **Espaçamento:** `var(--space-xs)` (4px), `var(--space-sm)` (8px), `var(--space-md)` (16px), `var(--space-lg)` (24px).
- **Cores & Superfícies:**
  - `var(--mat-sys-surface-variant)` para cards secundários de contas.
  - `var(--mat-sys-primary-container)` para o card de boas-vindas / onboarding.
  - `var(--mat-sys-on-surface-variant)` para textos secundários e metadados.

---

## 8. Critérios de Aceite

### Funcionais
1. [ ] **Dia Zero (Sem Contas):** Se o usuário não possui contas, o dashboard exibe o card de boas-vindas com o botão primário para criar a primeira conta, sem exibir botões quebrados de transação.
2. [ ] **Modo Privacidade:** O botão de privacidade oculta todos os valores monetários (`R$ •••••`) em todo o dashboard e lembra a preferência no `localStorage`.
3. [ ] **Carrossel de Contas:** Em mobile, as contas são exibidas em carrossel horizontal deslizável com saldo individual. Em desktop, são exibidas na barra lateral.
4. [ ] **Métricas do Mês:** Exibe corretamente Receita, Despesa e Saldo do período selecionado, com navegação fluida entre os meses.
5. [ ] **Transações Recentes:** Exibe as 5 últimas movimentações com ícone, categoria, conta e valor destacado.
6. [ ] **Ações Rápidas:** Atalhos diretos para Nova Transação, Nova Transferência e Importar Extrato funcionam sem erros.

### Não-Funcionais
1. [ ] Código 100% aderente ao Angular 21 Zoneless Signals.
2. [ ] Zero dependências externas ou frameworks CSS adicionais (mantém Angular Material 3 + SCSS).
3. [ ] Testes unitários atualizados em `dashboard.spec.ts` cobrindo:
   - Alternância de privacidade (`togglePrivacy`).
   - Computação de `hasAccounts` e `hasTransactions`.
   - Cálculo de `savingsRate`.
   - Navegação de mês.
4. [ ] Execução bem-sucedida do script `./scripts/test-summary.sh frontend`.

---

## 9. Plano de Execução no Modelo Híbrido

O desenvolvimento seguirá o protocolo Git Worktree rigoroso do projeto:

```bash
# 1. Commitar esta spec na branch develop
git add docs/superpowers/specs/2026-10-17-dashboard-pos-login-redesign.md
git commit -m "docs(spec): especificação do redesign do dashboard pós-login (pc e mobile)"

# 2. Criar worktree isolada para o Claude Code executar a codificação
git worktree add -b feat/dashboard-pos-login-redesign .worktrees/dashboard-pos-login develop

# 3. Execução pelo Claude Code:
#    - Atualização de dashboard.ts, dashboard.html e dashboard.scss
#    - Atualização de dashboard.spec.ts com testes unitários
#    - Validação com: cd frontend && npm test

# 4. Antigravity faz o Code Review e verificação de integridade antes do merge em develop.
```
