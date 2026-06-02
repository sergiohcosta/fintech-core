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

---

## 🔗 12. Adoção OpenAPI Spec-First (2026-05-26)

Migração completa para contrato formal de API, com geração de código em ambos os lados da stack.

### Motivação
Três problemas resolvidos de uma vez: documentação automática (Swagger UI), geração de client TypeScript no Angular eliminando modelos/services manuais, e contrato formal que impede desalinhamentos silenciosos (ex: enum `CONFIRMED` vs `PAID` detectado apenas em runtime antes).

### Backend
* **`api-spec/openapi.yaml`** na raiz do monorepo — fonte da verdade, ~650 linhas, 18 operationIds, 5 grupos de tags.
* **`openapi-generator-maven-plugin 7.4.0`** com `interfaceOnly=true` — gera interfaces Spring em `target/` durante `generate-sources`. Controllers implementam essas interfaces: mudança no YAML sem atualizar o controller = erro de compilação. O compilador virou o guardião do contrato.
* **`springdoc 2.8.9`** — versão 2.6.0 é incompatível com Spring Boot 4.0.1. Swagger UI disponível em `http://localhost:8080/swagger-ui.html`.
* **`importMappings`** aponta os types gerados para os DTOs existentes em vez de gerar novos — zero duplicação.
* **`getAuthenticatedUser()` via `SecurityContextHolder`** — `@AuthenticationPrincipal` não pode estar na assinatura de métodos de interface; padrão adotado: método privado em cada controller fazendo cast direto `(User)`.

### Frontend
* **Orval 8.13.0** (`npm run api:generate`) gera services Angular em `core/api/` com `mode: 'tags-split'` — um subdiretório por tag.
* **`fintechSaaSAPI.schemas.ts`** centraliza todos os tipos, interfaces e enums TypeScript gerados.
* **`auth/auth.service.ts` deletado após cada geração** — o AuthService gerencia estado (token, signals, router) além de HTTP; não pode ser substituído por client gerado.
* **`required` nos response DTOs do spec** — sem isso, Orval gera campos opcionais (`id?: string`) forçando `!` assertions nos templates. Adicionar `required: [id, name, ...]` no YAML resolve.
* Sete componentes migrados para os services gerados; modelos e services manuais de transactions, categories, dashboard e credit-cards **deletados**.
* **`.gitattributes`** marca `core/api/**/*.ts` como `linguist-generated=true` para reduzir ruído em diffs de PR.

### Fluxo de trabalho estabelecido
1. Editar `api-spec/openapi.yaml`
2. `mvn compile` → interfaces Spring atualizadas (falha = divergência de contrato)
3. `npm run api:generate` → services Angular atualizados; deletar `core/api/auth/auth.service.ts`
4. Copiar spec para `backend/src/main/resources/static/openapi.yaml`

### Bug corrigido durante os testes manuais
* `CategoryService` usava `jakarta.persistence.EntityNotFoundException` em vez de `com.fintech.api.exception.EntityNotFoundException` — `GlobalExceptionHandler` não capturava a exceção JPA, retornava 500 em vez de 404. Corrigido trocando o import.

---

## 🏦 13. Gestão de Contas — Account Management (2026-05-27)

Implementação fullstack do módulo de contas financeiras, substituindo a entidade `CreditCard` por uma abstração genérica `Account` com suporte a quatro tipos de conta e transferências double-entry.

### Motivação e decisões de design

* **`CreditCard` era demasiado específico** — o sistema precisava suportar conta corrente, investimentos, carteira física e cartão de crédito com o mesmo mecanismo de lançamentos.
* **`TRANSFER` removido do `TransactionType`** — transferências não são um tipo de lançamento, são uma coordenação de dois lançamentos (`EXPENSE` na origem + `INCOME` no destino) com um `transferId` UUID compartilhado. Esse modelo elimina saldo duplicado e permite rastrear as duas pontas de qualquer transferência.
* **Flags `countInLiquidBalance` / `countInNetWorth`** — investimentos têm saldo real mas não entram na projeção de caixa do dia. A decisão é por conta, não por tipo global.

### Backend

* **Migration Flyway V5** — cria tabela `accounts` (UUID, name, type, color, icon, active, countInLiquidBalance, countInNetWorth, tenant_id, created_by), migra dados de `credit_cards`, cria "Conta Padrão" por tenant para transações órfãs, adiciona `account_id` + `transfer_id` em `transactions`, dropa `credit_cards`.
* **`Account.java`** — `@Builder.Default private boolean active = true` (não `isActive` — Lombok geraria `isIsActive()`). `countInLiquidBalance` e `countInNetWorth` sem `@Builder.Default` — forçar o service a sempre setar explicitamente por tipo.
* **`CreditCardDetails.java`** — entidade separada `@OneToOne(fetch = LAZY)` com marca (`CardBrand`), últimos 4 dígitos, limite, dia de fechamento e vencimento.
* **`AccountRepository.calculateBalance`** — JPQL com `COALESCE(SUM(CASE WHEN type = INCOME THEN amount ELSE -amount END), 0)` excluindo `CANCELLED` — SUM retorna null em tabela vazia; COALESCE garante zero.
* **`AccountService.create()`** — aplica defaults por tipo: `CHECKING`/`CASH` → `countInLiquidBalance=true`; `INVESTMENT`/`CREDIT_CARD` → false.
* **`schemaMappings` no pom.xml** — `importMappings` só substitui imports/tipos em assinaturas. `@Schema(implementation = ...)` usa o nome raw do schema YAML, não o nome Java mapeado — necessário `schemaMappings: AccountResponse=AccountResponseDTO` para que a anotação referencia o DTO correto.
* 19/19 testes backend passando (AccountService 5, AccountController 4, TransactionService 4, TransactionController 2, AuthController 3, Application 1).

### Frontend

* **Orval regenerado** — `core/api/accounts/` criado, `core/api/credit-cards/` deletado manualmente (Orval não limpa diretórios antigos).
* **`AccountList`** — tabela Material com colunas nome, tipo (chip), saldo (CurrencyPipe pt-BR), flags (ícones com tooltip), ações. `registerLocaleData(localePt, 'pt-BR')` necessário no spec para o `CurrencyPipe` funcionar em testes.
* **`AccountForm`** — `toSignal(form.get('type').valueChanges)` + `computed(() => typeValue() === 'CREDIT_CARD')` para `isCreditCard`. Seção de campos do cartão visível apenas com `@if (isCreditCard())`. Mudança de tipo auto-atualiza `countInLiquidBalance` via `subscribe` no `ngOnInit`.
* **`TransactionForm` atualizado** — `creditCardId` substituído por `accountId` (obrigatório); opção `TRANSFER` removida do select de tipo.
* **`app.routes.ts`** — rotas `/accounts`, `/accounts/new`, `/accounts/:id` adicionadas; rotas `credit-cards` removidas.
* **Specs pré-existentes corrigidos** — imports errados (`Login` → `LoginComponent`, `Dashboard` → `DashboardComponent`, etc.) e `dashboard.html` strict null check (`s.balance ?? 0`).
* 7 novos testes frontend passando (AccountList 2, AccountForm 5).

### Bugs encontrados e corrigidos

* **`@Column(length=20)` em `CreditCardDetails.brand`** — SQL criava `VARCHAR(50)`; JPA mapeava com 20, causaria truncamento em produção. Corrigido para `@Column(length=50)`.
* **`vi.spyOn(...).mockReturnValue(of(...) as any)`** — Orval gera métodos com overloads; TypeScript tenta corresponder à assinatura mais específica (`Observable<HttpResponse<unknown>>`). Cast `as any` contorna sem perder cobertura de teste.
* **`feature/accounts-spec-migration` não mergeada antes de compilar o service** — `AccountsApi` não existia em `develop` ainda, causando falha de compilação na branch de service. Solução: merge Track 1 → develop antes de iniciar Track 2.

---

## 🎨 14. Melhorias no Formulário de Categorias — UX (2026-05-29)

Duas melhorias de usabilidade no `category-form`, puramente frontend, sem alterações de backend ou contrato de API.

### Issue #22 — Grid expansível de ícones

* **Problema:** o `mat-select` com lista vertical de 25 ícones era pouco visual — difícil comparar ícones num dropdown linear.
* **Solução:** bloco customizado com trigger clicável + grid 5×5 de `mat-icon`. Clicar no trigger abre/fecha o grid; clicar num ícone seleciona e fecha automaticamente. Ícone selecionado destacado com cor primária.
* **Signal `iconPickerOpen`** controla o estado aberto/fechado; método `toggleIconPicker()` bloqueia a abertura quando `inherited()` estiver ativo.
* **`selectedIcon = signal('folder')`** mantém o valor atual do ícone de forma reativa para o template Zoneless (substituiu `form.getRawValue().icon`, que não é um Signal).

### Issue #20 — Herança automática de cor e ícone do pai

* **Comportamento:** ao selecionar uma categoria pai, os campos `icon` e `color` são preenchidos com os valores do pai, desabilitados, e um hint "Herdado de X" aparece em ambos.
* **Ao remover o pai:** campos reabilitados, valores restaurados para os defaults (`folder` / `#3f51b5`).
* **Modo edição:** `forkJoin({ categories, cat })` garante que `parentOptions` já está populado antes do `patchValue`, evitando race condition. Após o patchValue, `applyInheritance(cat.parentId)` aplica o estado de herança manualmente (necessário porque `patchValue` usa `emitEvent: false`).
* **`emitEvent: false`** em todos os `patchValue`/`disable`/`enable` dentro de `setupParentInheritance` — evita que alterações programáticas sejam tratadas como ações do usuário e causem loops de `valueChanges`.
* **`takeUntilDestroyed(destroyRef)`** na subscription do `valueChanges` — evita memory leak ao destruir o componente.
* **`onSubmit()` usa `getRawValue()`** — campos `disabled` ficam fora do `.value` padrão mas são incluídos no `getRawValue()`.

### Testes

* 35/35 testes frontend passando — arquivo `category-form.spec.ts` criado do zero (TDD).
* Cobertura: icon picker (5 testes), herança de pai (7 testes), modo edição com categoria filha (1 teste).

---

## Issue #25 — Soft delete de categorias com fluxo de archive (2026-05-29)

### Problema
`DELETE /categories/{id}` lançava `DataIntegrityViolationException` (500 genérico) ao tentar excluir categorias com transações vinculadas. A FK `transactions.category_id` não tinha `ON DELETE CASCADE` ou `ON DELETE SET NULL`.

### Solução — Soft delete + fluxo de escolha
Em vez de hard delete ou ON DELETE SET NULL silencioso, o sistema agora dá ao usuário controle explícito:

**Backend:**
- **Migration V7**: coluna `deleted_at TIMESTAMP` em `categories`.
- **`CategoryHasTransactionsException`**: exceção específica com campo `transactionCount`, mapeada para 409 no `GlobalExceptionHandler`.
- **`CategoryRepository`**: queries renomeadas para `...AndDeletedAtIsNull`; novo `countByCategoryIdInAndTenantId` e `reassignCategories` (bulk UPDATE nativo) em `TransactionRepository`.
- **`CategoryService.delete()`**: conta transações na subárvore completa (`collectSubtreeIds` recursivo); lança 409 se count > 0; soft delete em cascata via `softDeleteSubtree()` se limpo.
- **`CategoryService.archive()`**: soft delete em cascata + `reassignCategories` opcional quando `targetCategoryId` fornecido; valida que destino não é descendente da categoria sendo arquivada.
- **Novo endpoint** `POST /api/categories/{id}/archive` com body opcional `{ targetCategoryId }`.

**Frontend:**
- **`CategoryArchiveDialog`**: abre quando DELETE retorna 409; duas ações — "Arquivar categoria" (mantém vínculo histórico via soft delete) ou "Mover e arquivar" (reassocia transações à categoria destino antes de arquivar). O dropdown de destino filtra arquivadas e a própria subárvore.

### Decisão de design
Soft delete (e não ON DELETE SET NULL) porque o FK continua íntegro no banco — permite restauração futura. A categoria desaparece das listagens mas transações históricas continuam referenciando a linha existente.

---

## Visibilidade de categorias arquivadas (2026-05-29)

### Bug corrigido
Filhos arquivados apareciam na listagem porque `CategoryResponseDTO.fromEntity()` mapeava `category.getChildren()` sem filtrar `deleted_at`. O `@OneToMany` retorna todos os filhos da relação, arquivados ou não.

**Fix:** `fromEntity(category, includeArchived)` — filtra filhos com `deletedAt != null` por padrão via stream filter antes de mapear recursivamente.

### Novas funcionalidades

**Backend:**
- `CategoryResponseDTO` ganhou campo `archived: boolean` (`deletedAt != null`).
- `CategoryRepository`: novo `findAllByTenantIdAndParentIsNull` (sem filtro) para uso com `includeArchived=true`.
- `CategoryService.findAllRoots(user, includeArchived)`: seleciona método de query e flag de mapeamento conforme parâmetro.
- `GET /api/categories?includeArchived=false` (default): query param documentado no spec.
- `TransactionResponseDTO.categoryArchived: boolean`: indica se a categoria vinculada está arquivada.

**Frontend:**
- **Listagem de categorias**: toggle "Mostrar arquivadas" (default off); arquivadas exibem ícone desbotado em cinza, nome taxado com tooltip "Categoria arquivada", botões Editar/Excluir disabled.
- **Listagem de transações**: categoria arquivada exibe nome taxado em cinza com tooltip (via `categoryArchived`).
- **Formulário de transação**: carrega com `includeArchived=true` para que a categoria arquivada já vinculada apareça no `mat-select` com strikethrough e `[disabled]="true"` — usuário vê o que era e pode trocar para uma ativa.

---

---

## 💳 15. Gerenciamento de Transações Parceladas (2026-06-02)

Implementação fullstack do gerenciamento completo de parcelamentos — da criação vinculada até exclusão/edição em massa com proteção de histórico financeiro.

### Problema resolvido

Parcelas criadas anteriormente eram "órfãs" — sem vínculo entre si. Impossível identificar quais transações pertenciam à mesma compra, excluir todas de uma vez ou editar em massa.

### Modelo de dados

* **Migration V8** — tabela `installment_groups` (id, description, total_amount, total_installments, account_id, category_id, tenant_id, created_at, updated_at) + coluna FK nullable `installment_group_id` em `transactions` + dois índices compostos.
* **`InstallmentGroup` entity** — `@ManyToOne` para account, category e tenant; `@CreationTimestamp` + `@UpdateTimestamp`. FK nullable preserva zero impacto em dados históricos.

### Backend

* **`TransactionService.create()`** — quando `totalInstallments > 1`, persiste `InstallmentGroup` antes do loop de parcelas e associa cada `Transaction` via `.installmentGroup(group)`.
* **`DELETE /api/transactions/{id}?scope=`** — três escopos: `SINGLE` (padrão, comportamento anterior), `THIS_AND_NEXT` (esta e as com `installmentNumber >= atual`), `ALL` (todo o grupo). Parcelas com `status=PAID` são ignoradas automaticamente; resposta retorna `{deleted, skippedPaid}`.
* **`PUT /api/transactions/{id}`** com `propagate: string[]` — propaga os campos selecionados (description, categoryId, accountId, amount, status) para parcelas futuras `PENDING` do mesmo grupo. Status nunca reverte `PAID → PENDING` via propagação.
* **`InstallmentGroupService`** — `findAll`, `findById`, `deleteGroup` (protege PAID), `patch` (bulk edit, só PENDING). `toDTO()` calcula `paidInstallments`, `pendingInstallments`, `nextDueDate` e `installmentAmount` a partir das transações.
* **`InstallmentGroupController`** — `GET /api/installment-groups`, `GET /{id}`, `DELETE /{id}`, `PATCH /{id}`. Implementa `InstallmentGroupsApi` gerada pelo OpenAPI Generator.
* **59 testes backend, 0 falhas**.

### OpenAPI spec

* Novos schemas: `DeleteInstallmentScope`, `DeleteInstallmentResultDTO`, `InstallmentGroupResponseDTO`, `InstallmentGroupPatchDTO`.
* `TransactionResponseDTO` ganhou `installmentGroupId`, `installmentGroupDescription`, `installmentNumber`, `totalInstallments`.
* `TransactionUpdateDTO` ganhou `propagate: string[]`.
* `pom.xml` atualizado com `importMappings` para os novos tipos.
* `npm run api:generate` (Orval) gerou `installment-groups.service.ts` + atualizou `transactions.service.ts`.

### Frontend

* **Listagem (`transaction-list`)** — lista "mista": transações avulsas como linhas simples; grupos como linhas colapsáveis com progress bar, nome do grupo, N×valor/parcela e botão "Excluir grupo". Lógica de agrupamento extraída em `transaction-list.utils.ts` (sem deps Angular) para testabilidade.
* **`DeleteInstallmentDialogComponent`** — radio group para selecionar escopo (esta / esta e próximas / todas); aviso inline quando escopo inclui múltiplas parcelas explicando que pagas não serão excluídas.
* **`transaction-form` melhorias**:
  * Toggle "É uma compra parcelada?" — oculto no modo edição e em transferências.
  * Seção expandível com: mode radio (valor total / valor da parcela), campo `totalInstallments`, tabela de prévia live.
  * Preview live usa `toSignal(control.valueChanges)` para reatividade real em Zoneless Angular (sem `toSignal`, `computed()` não reage a `FormControl.value`).
  * Seção de propagação no modo edição: 5 checkboxes opt-in (description, categoria, conta, valor, status).

### Lições técnicas

* **`finalGroup` em lambda**: Java exige effective final em closures; variável `group` que começa null precisa ser copiada para `finalGroup` antes do loop de `repository.save()`.
* **`computed()` + FormControls em Zoneless**: `computed()` só rastreia reads de signals. `form.controls.amount.value` não é signal — não dispara re-avaliação. Solução: `toSignal(control.valueChanges, { initialValue: ... })`.
* **Re-export de types com `isolatedModules`**: `export { SomeType }` falha — usar `export type { SomeType }`.
* **Flyway checksum mismatch**: migration amendada após ser aplicada ao banco local gera mismatch. Em dev com tabela vazia: remover a linha do `flyway_schema_history` e recriar o schema é mais limpo que `flyway:repair`.

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
- [x] Adoção OpenAPI spec-first (documentação + geração de código backend + frontend).
- [x] Gestão de Contas — Account Management (4 tipos, transferências double-entry, frontend TDD).
- [x] Melhorias UX no formulário de categorias (grid de ícones + herança de cor/ícone do pai).
- [x] Soft delete de categorias com fluxo de archive (issue #25) + visibilidade de arquivadas.
- [x] Dashboard empty state + posição financeira atual (2026-06-01).
- [x] Gerenciamento de transações parceladas — InstallmentGroup, delete por escopo, propagação de campos (2026-06-02).
- [ ] Filtros na listagem de transações (por período, tipo, status, conta).
- [ ] Gráficos no dashboard (evolução mensal, breakdown por categoria/conta).
- [ ] Tela de Transferências (fluxo específico para criar os dois lançamentos espelhados).
