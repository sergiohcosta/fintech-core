# 🚀 Projeto Fintech SaaS - Sumário da Jornada

Este documento detalha a evolução da arquitetura Fullstack desenvolvida, as decisões técnicas e os desafios superados desde o início.

---

## 🏛️ 1. Fundação da Arquitetura
O objetivo central é criar uma estrutura **SaaS Multi-Tenant**, onde uma única instância do sistema atende múltiplos clientes de forma isolada e segura.

* **Backend:** Spring Boot 4.0.1 com Java 21.
* **Frontend:** Angular 21 (Utilizando o novo padrão **Zoneless** para alta performance).
* **Banco de Dados:** PostgreSQL 16.
* **Versionamento de Banco:** Flyway (Migrations).
* **Comunicação:** REST API.

---

## 💾 2. Modelagem de Dados e Backend
Iniciamos definindo a hierarquia de dados e o isolamento entre contas:
1.  **Tenant:** Representa o cliente (Empresa ou Família). Cada Tenant possui seu próprio identificador único (UUID).
2.  **User:** Indivíduos pertencentes a um Tenant específico.
3.  **Segurança de IDs:** Implementação de UUIDs para evitar ataques de enumeração de IDs sequenciais.

### Desafios Superados:
* **Flyway Migrations:** Configuração de scripts SQL para garantir que a estrutura do banco seja versionada junto com o código.
* **DTOs (Data Transfer Objects):** Criação de contratos de entrada e saída para proteger as entidades de domínio e garantir validações rigorosas com `Bean Validation`.

---

## 🎨 3. Interface e Experiência do Usuário (Frontend)
Utilizamos o **Angular Material** para implementar o Material Design 3.

* **Zoneless Change Detection:** Implementação do `provideZonelessChangeDetection()`, eliminando a necessidade da `zone.js`.
* **Signals:** Uso de `signal`, `computed` e `effect` para gerenciamento de estado reativo e eficiente.
* **Componentização:** Criação do fluxo de cadastro (`RegisterComponent`) integrando o registro do Tenant e do Usuário Administrador em uma única transação de UI.

---

## 🔐 4. Segurança e Autenticação (JWT)
Este foi o marco de proteção do sistema, saindo do modelo de sessão para o modelo **Stateless**.

### Pilares da Segurança implementados:
* **Password Hashing:** Uso de `BCrypt` para garantir que senhas nunca sejam armazenadas em texto puro.
* **JWT (JSON Web Token):** Implementação de geração e validação de tokens assinados com chave secreta.
* **Security Filter:** Um filtro customizado que intercepta cada requisição HTTP para validar o token no Header `Authorization`.
* **RBAC (Role-Based Access Control):** Configuração inicial de permissões baseadas em funções (ex: `ROLE_USER`).

---

## 🛠️ 5. Resolução de Conflitos e Debugging
Principais correções realizadas durante o desenvolvimento:
* **TS2724/TS2322:** Correção de tipos e nomes de métodos que mudaram na versão estável do Angular 21.
* **Erro 403 Forbidden:** Diagnóstico de erros de autorização no Spring Security. Descobrimos que o Spring bloqueava rotas inexistentes (404) transformando-as em 403.
* **CORS Configuration:** Ajuste fino para permitir que o Frontend (porta 4200) se comunique com o Backend (porta 8080) com segurança.

---

## 🏗️ 6. Refatoração e Padronização de Categorias (Fullstack)
Implementação completa da gestão de categorias com foco em UX e integridade de dados.
* **Hierarquia Multinível:** Suporte a categorias pai/filho tanto no Backend (JPA Adjacency List) quanto no Frontend (Visualização em árvore na tabela e no seletor).
* **Segurança e Validação:** Lógica anti-circular no Frontend para impedir que uma categoria seja pai de si mesma.
* **Padronização Visual:** Replicação dos padrões de design de 'Credit Cards' para Categorias, garantindo consistência em todo o sistema (Grids, Tabelas, MatSnackBar).

---

## 🛡️ 7. Segurança e Fluxo de Autenticação Refinado
Melhorias críticas na proteção de dados e na experiência de acesso.
* **Roles com Enum:** Substituição de Strings por Enums (`UserRole`) no Backend, garantindo tipagem forte e hierarquia de permissões (Admin + User).
* **Validação de Token no Front:** O `AuthGuard` agora verifica a expiração (`exp`) do JWT, impedindo acessos com tokens vencidos.
* **Redirecionamento Inteligente:** Usuários já autenticados são redirecionados automaticamente do Login/Register para o Dashboard.

---

## 💻 8. Infraestrutura de Desenvolvimento (DX)
* **Spring Boot DevTools:** Configuração de auto-restart otimizada para ambiente WSL/VSCode, agilizando o ciclo de feedback no Backend.
* **Geração de GEMINI.md:** Criação de documentação viva para contexto do agente, detalhando arquitetura e convenções do projeto.

---

## 💸 9. CRUD Completo de Transações Financeiras (Fullstack)

Implementação end-to-end do módulo de transações, cobrindo criação, listagem, edição e exclusão com isolamento de tenant em todas as operações.

### Backend
* **Endpoints REST:** `GET /api/transactions`, `GET /:id`, `POST`, `PUT /:id`, `DELETE /:id` — todos protegidos por JWT e escopados ao tenant do usuário autenticado.
* **Parcelamento:** criação em parcelas gera múltiplos registros automaticamente, com divisão do valor e incremento mensal de data.
* **Isolamento de ownership:** `findByIdAndTenant` no repository garante que edição e exclusão só funcionam para transações do próprio tenant — 404 intencional caso o id seja de outro tenant (não vaza informação de existência).
* **Dirty checking do Hibernate:** método `update()` não chama `repository.save()` explicitamente — o Hibernate detecta mudanças na entidade gerenciada e gera o `UPDATE` automaticamente no commit da transação.

### Frontend
* **Model e Service:** `TransactionResponse`/`TransactionRequest` tipados; service com `list`, `getById`, `create`, `update`, `delete`.
* **Listagem:** tabela com badges de tipo (Receita/Despesa/Transferência) e status (Pendente/Pago/Cancelado), formatação monetária em pt-BR, botões de editar e excluir por linha.
* **Formulário unificado:** mesmo componente (`TransactionForm`) detecta modo criação vs. edição via `ActivatedRoute` — pré-preenche campos ao editar, exibe título dinâmico.
* **Exclusão com confirmação:** reutiliza `ConfirmationDialogComponent` existente, padrão idêntico ao de categorias e cartões.
* **Locale pt-BR:** registro global de `LOCALE_ID` e `MAT_DATE_LOCALE` — corrige `CurrencyPipe` e formato do `MatDatepicker`.

### Bugs corrigidos no caminho
* `EntityNotFoundException` da JPA usada no service em vez da customizada — impedia o `GlobalExceptionHandler` de interceptar e retornar 404 (retornava 500).
* `TransactionStatus.CONFIRMED` no frontend vs `PAID` no backend — desalinhamento de contrato de enum.
* Locale `pt-BR` não registrado — valor monetário em branco na listagem e datepicker em formato americano.
* `GET /api/transactions/:id` esquecido no planejamento — descoberto ao testar o fluxo de edição.

### Infraestrutura de desenvolvimento
* **MCPs configurados:** `postgres` (queries diretas ao banco), `context7` (documentação atualizada de libs) e `github` (gestão de issues) — registrados via `claude mcp add` em `~/.claude.json`.

---

## 🧭 10. Shell de Navegação — Toolbar Superior + Sidenav Lateral

Implementação do layout principal da aplicação autenticada usando o **Shell Pattern**.

### Arquitetura adotada
* **ShellComponent** (`components/shell/`) — componente de layout que contém `mat-toolbar` (topo) e `mat-sidenav-container` (lateral + conteúdo), com seu próprio `<router-outlet>` para as features.
* **Layout Route** — rota com `path: ''` (sem `pathMatch: 'full'`) como pai de todas as rotas protegidas. `canActivate: [authGuard]` aplicado uma única vez no pai, propagando para todos os filhos automaticamente.
* Login e Register ficam fora da hierarquia do shell — sem navbar/sidenav nessas telas.

### Decisões técnicas
* `sidenavOpened = signal(true)` para controle do toggle — consistente com modelo Zoneless (sem zone.js).
* `userName = computed(() => authService.currentUser()?.name)` — relê o signal do AuthService, atualiza a toolbar sem re-renders desnecessários.
* Destaque do item ativo via `routerLinkActive` com tokens M3 (`--mat-sys-secondary-container`) — sem cores hardcoded.
* `@for` control flow syntax (Angular 17+) para gerar os itens do sidenav.

### Conceitos ensinados
* Por que `canActivate` no pai protege filhos — pipeline de validação em cascata percorrido de cima para baixo.
* Diferença entre `canActivate` e `canActivateChild`.
* Como `authGuard` funciona internamente — `jwtDecode` decodifica sem validar assinatura, verifica campo `exp`; guards frontend são segurança de UX, não de dados.
* Por que `inject()` funciona dentro de funções guard — executado no contexto de injeção do router.

### Pendências identificadas
* Componente `navbar` antigo (`components/navbar/`) estava vazio/obsoleto — **deletado nesta sessão**.

---

## 📊 11. Dashboard com Resumo Financeiro

Implementação fullstack do dashboard com totais mensais de receita, despesa e saldo, navegação de período e lista de transações recentes.

### Backend
* **`DashboardSummaryDTO`** — Java `record` com `totalIncome`, `totalExpense`, `balance`, `period` (YearMonth). Factory method `DashboardSummaryDTO.of(period, income, expense)` calcula o saldo internamente.
* **Query JPQL com `COALESCE(SUM)`** — `SUM` retorna `null` quando não há registros; `COALESCE(..., 0)` garante BigDecimal zero. Query filtra por `tenant`, `type`, `status <> CANCELLED` e intervalo de datas (`BETWEEN start AND end`).
* **`DashboardService`** — `@Transactional(readOnly = true)`, duas chamadas ao repository (uma por tipo), monta o DTO com `DashboardSummaryDTO.of()`.
* **`DashboardController`** — `GET /api/dashboard/summary?month=yyyy-MM`. Parâmetro recebe `YearMonth` diretamente via `@DateTimeFormat(pattern = "yyyy-MM")` — Spring retorna 400 automaticamente para formato inválido.

### Frontend
* **Padrão `toSignal + toObservable + switchMap`** — substitui o `subscribe() + signal.set()` existente nos outros componentes. `switchMap` cancela a request anterior ao trocar de mês; `toSignal()` faz o unsubscribe automático ao destruir o componente.
* **Navegação de mês** — `selectedYear` e `selectedMonthIndex` como signals separados; `selectedMonth` e `monthLabel` como `computed()`. Botão "próximo" desabilitado quando `isCurrentMonth()`.
* **3 cards** — Receita (verde), Despesa (vermelho), Saldo (azul positivo / vermelho negativo) com ícones e cores semânticas via `color-mix()`.
* **Transações recentes** — Reutiliza `TransactionService.list()` + `toSignal()`, exibe as 5 primeiras via `.slice(0, 5)` no template.

### Boas práticas aplicadas / ajustadas
* `@DateTimeFormat(pattern = "yyyy-MM")` em vez de `String` — validação delegada ao Spring MVC.
* Duas queries limpas com `COALESCE(SUM)` em vez de uma JPQL com `CASE WHEN` (verbosa e propensa a erros com enums qualificados).
* `toSignal()` + `switchMap` em vez de `subscribe()` manual — cancela requests em voo, unsubscribe automático.
* Teste end-to-end antes do commit: backend iniciado, login com usuário real, transações criadas via API (incluindo uma `CANCELLED`), verificação de que o saldo excluiu corretamente o valor cancelado.

### Processo de verificação adotado
* Backend compilado (`./mvnw compile`) antes de codar o frontend.
* Endpoint testado via `curl` com JWT real antes de abrir o browser.
* Transação `CANCELLED` criada propositalmente para validar que o `COALESCE(SUM ... WHERE status <> CANCELLED)` funciona.
* Playwright para screenshot do dashboard renderizado e da navegação entre meses.

---

## 📅 Status Atual
- [x] Estrutura de Pastas e Projetos.
- [x] Banco de Dados e Migrations Iniciais.
- [x] Cadastro de Tenant/Usuário (Fullstack).
- [x] Infraestrutura de Segurança JWT (Backend).
- [x] Implementação da Tela de Login (Frontend).
- [x] Gestão Completa de Categorias (Hierárquico).
- [x] Padronização Visual de Listas e Formulários.
- [x] CRUD Completo de Transações Financeiras (Fullstack).
- [x] Shell de Navegação (Toolbar + Sidenav).
- [x] Dashboard com resumo financeiro (Receita / Despesa / Saldo por período).
- [ ] Filtros na listagem de transações (por período, tipo, status).
- [ ] Gráficos no dashboard (evolução mensal, breakdown por categoria).
