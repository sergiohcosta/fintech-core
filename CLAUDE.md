# Projeto: Fintech SaaS Multi-Tenant

Plataforma SaaS de gestão financeira multi-tenant, com isolamento e segurança como princípios centrais. Uma única instância atende múltiplos clientes (famílias ou empresas) de forma isolada.

---

## 🎓 Objetivo Central do Desenvolvedor

> **Este projeto é, antes de tudo, uma jornada de aprendizado.**

O objetivo do desenvolvedor não é apenas entregar features, mas **dominar profundamente** cada tecnologia, padrão e decisão arquitetural aplicada no projeto. Velocidade de entrega é secundária à compreensão.

### Como a IA deve atuar

Você (Claude) atua como **mentor técnico sênior**, não como gerador de código. Isso muda concretamente o seu comportamento:

**1. Planejar antes de codar — sempre que houver complexidade**

Antes de implementar qualquer feature que envolva mais de um arquivo, conceito novo ou decisão arquitetural, apresente um **plano explícito** contendo:
- O que será feito e em que ordem
- Quais arquivos serão criados ou alterados e por quê
- Quais conceitos/tecnologias serão aplicados
- Que alternativas existiam e por que esta foi escolhida
- O que o desenvolvedor deve revisar com atenção especial

Só execute após o desenvolvedor revisar e aprovar o plano. Se for uma alteração trivial (corrigir typo, ajustar import), pode executar direto — use bom senso.

**2. Ensinar enquanto faz — não só "o quê", mas "por quê"**

Toda vez que aplicar um conceito relevante, **explique-o**. Não assuma conhecimento prévio sólido em:
- Spring (IoC, beans, autowiring, ciclo de vida, transações, security filters)
- JPA/Hibernate (lazy/eager, N+1, cascade, fetch types, queries derivadas vs JPQL)
- Angular moderno (Signals, Zoneless, change detection, lifecycle, DI hierárquica)
- RxJS (operadores, subjects, schedulers, quando usar e quando evitar)
- Padrões arquiteturais (DDD, hexagonal, camadas, DTO, repository)
- Segurança (JWT, CSRF, CORS, hashing, RBAC)
- SQL/Postgres (índices, planos de execução, transações, isolamento)

Estilo de ensino preferido:
- **Conciso, mas com profundidade.** Evite explicações superficiais ("é só pra organizar"). Vá à raiz: o que isso resolve, que problema existiria sem isso, o que está acontecendo "por baixo".
- **Conecte conceitos.** Quando explicar `@Transactional`, conecte com o ciclo de vida do EntityManager. Quando explicar `signal()`, conecte com o modelo de reatividade do Angular Zoneless.
- **Use analogias quando ajudar**, mas não force.
- **Aponte armadilhas comuns** ("isso costuma quebrar quando...", "cuidado com... porque...").

**3. Justificar escolhas técnicas**

Ao escrever código, comente brevemente o "porquê" das decisões não óbvias. Exemplos:
- Por que `record` em vez de classe com Lombok para este DTO?
- Por que `computed()` em vez de armazenar em outro `signal`?
- Por que `@Transactional(readOnly = true)` aqui?
- Por que esta query precisa de índice composto?

Não polua o código com comentários óbvios — só os de valor pedagógico.

**4. Provocar reflexão**

Ao terminar uma feature, faça perguntas que consolidam aprendizado:
- "Você consegue explicar com suas próprias palavras o que esse filtro faz?"
- "Por que escolhemos um Service em vez de colocar a lógica no Controller?"
- "O que aconteceria se removêssemos `@Transactional` deste método?"

Não exagere — uma ou duas perguntas pertinentes ao final de blocos significativos.

**5. Oferecer aprofundamento opcional**

Após explicar o básico necessário, ofereça caminhos extras: *"Se quiser ir mais fundo em como o Spring Security monta a SecurityFilterChain, posso mostrar."* Deixe o desenvolvedor escolher o ritmo.

**6. Não pular etapas "para economizar tempo"**

Se o desenvolvedor pedir algo que parece simples mas envolve um conceito não dominado, **pare e ensine**. Exemplo: ele pede "adiciona paginação nessa listagem" — antes de codar, explique brevemente como `Pageable` funciona no Spring Data, o que é `Page<T>` vs `Slice<T>`, e como isso se reflete no contrato com o frontend.

**7. Idioma**

Trabalhe sempre em **português (PT-BR)**: explicações, comentários pedagógicos no código, mensagens de commit sugeridas, documentação. Nomes de variáveis, classes, métodos e identificadores permanecem em inglês (padrão da indústria).

---

## 🏛️ Visão Geral

Aplicação Fullstack SaaS com arquitetura moderna para suporte a múltiplos tenants em uma única instância do sistema.

### Stack
- **Backend:** Java 21, Spring Boot 4.0.1, Spring Security, JPA/Hibernate
- **Frontend:** Angular 21 com Zoneless Change Detection, Angular Material 3, Vitest
- **Banco:** PostgreSQL 16, Flyway para migrations
- **Infra local:** Docker Compose (PostgreSQL + pgAdmin)
- **Autenticação:** JWT Stateless

### Arquitetura
- **Multi-Tenancy:** isolamento via entidade `Tenant`. Entidades de negócio (User, Transaction, Category, etc.) referenciam um `Tenant` por UUID.
- **Segurança:** toda requisição autenticada por JWT. Backend valida via `SecurityFilter` e popula o contexto de segurança. Frontend anexa token via `authInterceptor`.
- **Fluxo de dados:** DTOs para toda comunicação de API. Entidades JPA nunca são expostas diretamente.
- **IDs:** UUIDs em todas as entidades expostas externamente (anti-enumeration).

---

## 🛠️ Como Rodar

### 1. Infraestrutura
```bash
docker compose up -d
```
- **PostgreSQL:** `localhost:5432` (user: `admin`, senha: `secret`, db: `fintech`)
- **pgAdmin:** `http://localhost:5050` (user: `admin@fintech.com`, senha: `admin`)

### 2. Backend
```bash
cd backend
./mvnw spring-boot:run
```
- Porta: `8080`
- Testes: `./mvnw test`

### 3. Frontend
```bash
cd frontend
npm install
npm start
```
- Porta: `4200`
- Testes: `npm test` (Vitest)

---

## 📏 Convenções de Desenvolvimento

### Workflow de Branches e PRs

**Regras invioláveis:**
- Toda branch de feature parte de `develop` (nunca de `main` ou de outra feature branch)
- Ao concluir uma feature com sucesso, fazer merge imediato na `develop` local e push
- PRs devem ser o mais cumulativos possível: agrupar issues relacionadas da mesma sessão em uma única PR em vez de abrir uma por issue
- PRs sempre apontam para `main` e partem de `develop` (o fluxo é `feature → develop → PR → main`)
- Nunca fazer merge de `develop` → `main` diretamente; sempre via PR com revisão
- Deletar branches locais após o merge em `develop` para manter o repositório limpo

**Fluxo padrão:**
1. `git checkout -b feature/issue-XYZ develop`
2. Implementar, commitar, testar
3. `git checkout develop && git merge feature/issue-XYZ && git push origin develop`
4. Abrir PR: `feature/issue-XYZ → main` (ou acumular issues relacionadas numa PR única)
5. `git branch -d feature/issue-XYZ`

### Commits

- Mensagens em português, descritivas, no imperativo ("adiciona", "corrige", "implementa")
- **Nunca incluir co-autoria (`Co-Authored-By`) nas mensagens de commit**

### Backend

**Regras invioláveis:**
- **NUNCA** usar `spring.jpa.hibernate.ddl-auto=update`. Toda mudança de schema é via migration Flyway em `src/main/resources/db/migration/`.
- Migrations já aplicadas em ambientes superiores são **imutáveis**. Correção é sempre via nova migration.
- Nunca expor entidade JPA diretamente em controller. Sempre DTO.
- Toda query de dados de negócio deve ser escopada pelo `Tenant` do usuário autenticado. **Vazamento de tenant é o bug mais grave possível neste projeto.**

**Padrões:**
- Arquitetura: Controller → Service → Repository
- DTOs com Bean Validation (`@NotNull`, `@NotBlank`, `@Email`, `@Size`, etc.)
- Lombok permitido com `@Data`, mas atenção ao `@EqualsAndHashCode` — preferir inclusão explícita de ID para evitar problemas com entidades JPA
- Tratamento de erro centralizado via `GlobalExceptionHandler`
- Roles tipadas com Enum (`UserRole`), nunca String
- Testes: JUnit 5 + Mockito; integração com Testcontainers preferível a H2

### Frontend

**Regras invioláveis:**
- Projeto é **Zoneless** (`provideZonelessChangeDetection()`). Não usar APIs que dependam de `zone.js`.
- **Signals primeiro** para estado local (`signal`, `computed`, `effect`). RxJS apenas para streams genuinamente assíncronos (HTTP, WebSocket, eventos).
- **SCSS + Angular Material 3** para estilização. Não introduzir TailwindCSS sem solicitação explícita.
- TypeScript estrito. Proibido `any` — usar `unknown` e narrowing quando o tipo for genuinamente incerto.

**Padrões:**
- Standalone components (sem NgModule, exceto se já existirem por legado)
- Features organizadas em `features/`, código compartilhado em `core/` ou `shared/components/`
- Services com `providedIn: 'root'` por padrão
- Lazy loading por feature route
- Validação anti-circular em estruturas hierárquicas (ex: categorias pai/filho)

### Segurança

- **JWT Secret:** em `application.properties` (em produção, via variável de ambiente)
- **CORS:** configurado em `SecurityConfigurations.java` — permitir frontend (porta 4200 em dev)
- **AuthGuard frontend:** valida expiração (`exp`) do token antes de permitir navegação
- **Redirecionamento:** usuários autenticados em `/login` ou `/register` vão direto pro dashboard
- **Senhas:** sempre `BCrypt`. Nunca logar, nunca retornar em DTO de resposta.

**Regra inviolável — defesa em profundidade para controle de acesso:**

Toda alteração que envolva permissões de acesso, visibilidade de recursos ou restrição por role **deve ser validada em ambas as camadas**:

| Camada | O que fazer |
|--------|-------------|
| **Backend** | Adicionar (ou confirmar) regra em `SecurityConfigurations.java` com `hasRole(...)` para o endpoint afetado. Cobrir com teste de controller que verifica 403 para a role não autorizada. |
| **Frontend** | Ocultar elemento/rota via `@if (isAdmin())` ou equivalente. Não chamar endpoints que o usuário não tem permissão de acessar (evita 403 desnecessário). |

Ocultar no frontend **não substitui** proteção no backend. O frontend é contornável; o backend é a última linha de defesa. A consistência entre as duas camadas evita tanto falhas de segurança quanto erros de UX (tela quebrando com 403 inesperado).

**Exemplo concreto (issue #24):** `GET /api/members` e `GET /invites` são exclusivos de ADMIN — protegidos em `SecurityConfigurations.java` com `hasRole("ADMIN")` **e** ocultos no frontend via `isAdmin()` no sidenav e no `forkJoin` do `TeamComponent`.

### Dataset de Testes — Família Costa

O projeto mantém um dataset realista (`V13__seed_dev.sql`) que deve ser tratado como **artefato vivo** — parte da especificação do sistema. Mantê-lo desatualizado equivale a ter documentação errada.

**Regra inviolável:** toda alteração que envolva banco de dados **deve** atualizar o dataset de testes para contemplar as mudanças realizadas. Não existe "vou atualizar depois" — a atualização faz parte da entrega, não é opcional.

**Artefatos:**
- `backend/src/main/resources/db/seed/V13__seed_dev.sql` — seed Flyway, perfil `dev` apenas
- `backend/src/test/resources/sql/seed_base.sql` / `cleanup.sql` — fixture para Testcontainers
- `docs/http/seed-dataset.http` — HTTP collection IntelliJ/VS Code
- Spec completa: `docs/superpowers/specs/2026-06-09-test-dataset-design.md`

**Regra para SDD e TDD — ao implementar qualquer feature:**

| Situação | Ação obrigatória |
|----------|-----------------|
| Nova tabela de negócio adicionada | Inserir dados representativos no `V13__seed_dev.sql` |
| Nova coluna relevante em tabela existente | Atualizar os INSERTs do `V13__seed_dev.sql` |
| Nova coluna adicionada por migration | Atualizar os INSERTs existentes no `V13__seed_dev.sql` para incluir o novo campo |
| Nova entidade necessária para setup mínimo de testes | Atualizar `seed_base.sql` |
| Novo endpoint ou novo parâmetro de endpoint | Adicionar request em `docs/http/seed-dataset.http` |
| Feature puramente de frontend / refatoração sem schema | Nenhuma atualização necessária |

**Ao atualizar `V13__seed_dev.sql`:** manter o padrão de UUIDs predefinidos. Novas entidades recebem UUIDs na série correspondente (ver spec). Nunca usar `gen_random_uuid()` para entidades que precisam de cross-reference.

**Atenção — posição do arquivo seed:** o seed deve sempre ter versão maior que todas as migrations de schema. Ao adicionar uma nova migration (ex: V14), verificar se o seed precisa ser renomeado para V15 (após o novo schema).

**Credenciais:**
- Dev (banco com seed): `carlos@costa.com` / `costa123`
- Testes de integração (`seed_base.sql`): `admin@test.com` / `admin123`

**Reset do banco dev:**
```bash
docker exec fintech-postgres psql -U admin -d postgres -c "DROP DATABASE fintech; CREATE DATABASE fintech;"
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

---

## 📂 Estrutura de Diretórios

- `backend/` — aplicação Spring Boot
- `frontend/` — aplicação Angular
- `.docker/` — dados persistentes do banco (gitignored)
- `summary.md` — referência de especificação técnica (domínio, contratos de API, regras de negócio)
- `.github/ISSUE_TEMPLATE/` — templates de issue (bug, feature, chore) + config.yml
- `.github/pull_request_template.md` — template de PR com checklist das regras invioláveis

---

## 🎯 Status Atual e Próximos Passos

**Concluído:**
- Estrutura inicial dos projetos
- Migrations iniciais e modelagem de Tenant/User
- Cadastro fullstack de Tenant + Usuário Admin
- Infraestrutura JWT (backend + frontend)
- Tela de Login
- Gestão completa de Categorias (hierárquica, multinível)
- Padronização visual de listas e formulários
- CRUD completo de Transações Financeiras (fullstack)
- Shell de Navegação (Shell Pattern — toolbar + sidenav)
- Dashboard com resumo financeiro (receita / despesa / saldo por período)
- OpenAPI spec-first (documentação automática + geração de código backend + frontend via Orval)
- Gestão de Contas — Account Management (4 tipos, transferências double-entry, frontend TDD):
  - **`countInLiquidBalance`:** inclui a conta no saldo líquido disponível (dinheiro acessível imediatamente). Default `true` para CHECKING e CASH; `false` para INVESTMENT e CREDIT_CARD. Alimenta `totalAccountBalance` no Dashboard via `sumNetLiquidBalanceByTenant()`.
  - **`countInNetWorth`:** inclui a conta no patrimônio líquido total (visão ampla, incluindo investimentos). Default `true` para todos os tipos. Campo armazenado, **ainda não consumido por query** — reservado para futura tela de Patrimônio Total.
  - Frontend auto-ajusta `countInLiquidBalance` ao trocar tipo de conta; usuário pode sobrescrever.
- Melhorias UX no formulário de categorias (grid de ícones + herança de cor/ícone do pai)
- **Soft delete de categorias com fluxo de archive** (issue #25, 2026-05-29):
  - `DELETE /categories/{id}` retorna 409 com `transactionCount` se subárvore tem transações
  - `POST /categories/{id}/archive` — soft delete em cascata + reassociação opcional de transações
  - `CategoryArchiveDialog` no frontend com duas ações: arquivar ou mover+arquivar
  - Migration V7: coluna `deleted_at` em `categories`
- **Visibilidade de categorias arquivadas** (2026-05-29):
  - `GET /api/categories?includeArchived=true` — retorna árvore completa incluindo arquivadas
  - Listagem de categorias: toggle "Mostrar arquivadas" (default off); arquivadas com ícone desbotado, nome taxado, botões disabled
  - `TransactionResponseDTO.categoryArchived: boolean` — listagem de transações exibe nome taxado com tooltip
  - Formulário de transação: categorias arquivadas aparecem disabled no select ao editar transações históricas
  - Bug fix: filhos arquivados não apareciam filtrados no `@OneToMany children`
- **Dashboard empty state + posição financeira** (2026-06-01):
  - `DashboardSummaryDTO` ganhou `transactionCount` e `totalAccountBalance`
  - Card "Posição atual" sempre visível; meses sem movimentação exibem empty state
- **Gerenciamento de transações parceladas** (2026-06-02):
  - Migration V8: tabela `installment_groups` + FK nullable em `transactions`
  - `TransactionService.create()` cria `InstallmentGroup` ao parcelar
  - `DELETE /api/transactions/{id}?scope=SINGLE|THIS_AND_NEXT|ALL` com proteção de parcelas paidas
  - `PUT /api/transactions/{id}` com `propagate: string[]` para propagar campos a parcelas futuras pendentes
  - `InstallmentGroupService` + `InstallmentGroupController` (list, get, delete, patch)
  - Frontend: cada parcela como linha individual na listagem (não mais grupo colapsável); expandir linha exibe detalhes do grupo (progress bar, pagas/total, botão excluir pendentes); `DeleteInstallmentDialog`; formulário com toggle parcelamento + preview live + propagação na edição
- **Fatura (Invoice) para cartão de crédito** (2026-06-02):
  - Migration V9: tabela `invoices` + FK nullable `invoice_id` em `transactions`
  - Ciclo de vida: `OPEN → CLOSED → PAID`; criação lazy na primeira transação do período (`getOrCreate`)
  - `closingDay` / `dueDay`: compras até o dia de fechamento → fatura do mês corrente; após → próximo mês. `dueDay >= closingDay` → vencimento no mesmo mês; caso contrário → mês seguinte
  - Parcelas de cartão: todas com `date = data da compra`; cada uma associada à fatura do seu mês (`invoiceMonth.plusMonths(i)`)
  - Dashboard: queries usam `invoice.dueDate` como referência de período para transações de cartão, `t.date` para as demais
  - `InvoiceController`: `GET /api/invoices?accountId=`, `GET /api/invoices/{id}`, `POST /{id}/close`, `POST /{id}/pay`
  - Frontend: chip de fatura na listagem de transações; preview de parcelas exibe mês da fatura e vencimento
- **Ordenação e exibição de datas na listagem de transações** (2026-06-02):
  - **Regra de sort** (`TransactionService.effectiveSortDate()`): parcelas de cartão → `invoice.dueDate`; todas as demais → `t.date`
  - **Regra de exibição da coluna "Data"**:
    - Parcela de cartão (`installmentGroupId != null`): exibe `invoiceDueDate` — todas as parcelas têm a mesma data de compra; o `dueDate` é o que as distingue e é coerente com a posição na lista
    - Transação avulsa de cartão (`installmentGroupId == null`, mesmo com fatura): exibe `t.date` — data de compra é única e relevante
    - Qualquer transação de conta regular: exibe `t.date`
  - Rationale: `t.date` de parcelas de cartão é sempre a data da compra (idêntica em todas as parcelas do grupo); o `dueDate` distribui cada parcela no mês em que impacta o orçamento. Para transações avulsas (incluindo avulsas de cartão), a data real da compra é a informação relevante.

- **Bug fix: dashboard excluía transações sem fatura** (2026-06-02):
  - `countByTenantAndPeriod` e `sumByTenantAndTypeAndPeriod` em `TransactionRepository` usavam `t.invoice.dueDate` no WHERE, o que fazia o Hibernate gerar um INNER JOIN implícito com `invoices`
  - O INNER JOIN excluía todas as transações sem `invoice_id` (contas corrente, dinheiro, investimento), fazendo o branch `t.invoice IS NULL` ser sempre falso
  - Correção: `LEFT JOIN t.invoice inv` explícito no JPQL; usar `inv` em vez de `t.invoice` nas condições
- **Correções e melhorias de UX** (2026-06-05, PR #40 — issues #34, #38, #39):
  - **fix #34:** `RouterLink` adicionado ao `imports[]` do `RegisterComponent` standalone — sem isso, `routerLink` era ignorado e o botão "Ir para login" pós-cadastro não navegava
  - **fix #38:** campo `document` (CPF/CNPJ) removido do fluxo de cadastro de tenant (`TenantRegistrationDTO`, `TenantRegistrationService`, formulário frontend); coluna permanece nullable no banco para uso futuro adequado
  - **feat #39:** seleção de conta movida para o topo do formulário de transação, antes de tipo e status — conta determina campos disponíveis (parcelamento só aparece para CREDIT_CARD)
- **Gerenciamento de faturas — frontend** (issue #42):
  - Rota `/invoices` com lazy loading; item no sidenav visível apenas para contas `CREDIT_CARD`
  - `InvoiceListComponent`: seletor de conta, listagem com status/valores/datas, ações fechar/pagar
  - `computeBreakdown`: utilitário de lógica pura (testável sem TestBed) para breakdown por categoria
  - `InvoiceDetailComponent`: resumo financeiro + breakdown por categoria
  - Fixes: `LazyInitializationException` em `GET /api/invoices/{id}`, projeção de `mat-icon`, null-check em `onClose`/`onPay`, reset de loading via `finalize`, `aria-label` em links
- **Logging estruturado com MDC** (issue #43):
  - `RequestIdFilter`: UUID por requisição no MDC + header `X-Request-ID` na resposta
  - `SecurityFilter`: `userId` e `tenantId` no MDC após autenticação; `WARN` em token inválido
  - `GlobalExceptionHandler`: `log.error()` com stack trace em erros 5xx
  - `InvoiceService`: `log.info()` em transições de estado (fechar/pagar fatura)
  - dev: padrão legível com `requestId`/`userId` visíveis; prod: JSON estruturado (`logstash`)
- **Comportamento de fechar e pagar fatura (issue #45, 2026-06-06)**:
  - Fechar (`OPEN → CLOSED`): marcador administrativo, sem side effects; novas transações ainda permitidas com aviso visual
  - Pagar (`CLOSED → PAID`): em `@Transactional` única — transações `PENDING` → `PAID` via `@Modifying` batch; cria `EXPENSE` na conta de origem se `total > 0`; fatura → `PAID`
  - `InvoicePayDTO { sourceAccountId }` + validações: conta não pode ser `CREDIT_CARD` (422), fatura deve estar `CLOSED` (422)
  - `GlobalExceptionHandler`: `IllegalStateException` → 422 (antes caía no handler genérico → 500)
  - `InvoicePayDialog`: `mat-select` de contas elegíveis (sem `CREDIT_CARD`); edge case sem contas disponíveis
  - Botão "Fechar" só em `OPEN`; botão "Pagar" só em `CLOSED` — tanto em `InvoiceList` quanto em `InvoiceDetail`
  - `TransactionResponseDTO` ganhou `categoryPath` (ex: `"Pets → Ração"`) e `categoryIcon` — breakdown da fatura exibe path completo + ícone
  - Fix: `mat-select` exibia nome da ligatura do ícone (ex: `"credit_card Nubank"`) — corrigido com `<mat-select-trigger>` no formulário de transação

- **Filtros na listagem de transações (issue #41, 2026-06-08)**:
  - 5 query params opcionais na OpenAPI spec + codegen: `accountId`, `status`, `type`, `startDate`, `endDate`
  - `findAllByTenantWithFilters` no `TransactionRepository` com JPQL dinâmico (`(:param IS NULL OR condição)`)
  - Regra de data: parcelas de cartão (`installmentGroup IS NOT NULL AND inv IS NOT NULL`) filtram por `inv.dueDate`; demais por `t.date`
  - Validação no service: `startDate`/`endDate` devem ser informados juntos ou omitidos (`IllegalArgumentException` → 400)
  - `effectiveSortDate` corrigido para `installmentGroup != null && invoice != null` (alinhado com a regra do JPQL)
  - Frontend: `TransactionFiltersComponent` com seletor de mês (◀/▶ preenche start/end), filtros de conta/status/tipo, toggle de agrupamento
  - Agrupamento por período: `period-header` row kind no `buildDisplayRows`; mesma `mat-table` com row predicates distintos
  - `groupByEffectiveMonth`: agrupa por mês efetivo (parcelas → `invoiceDueDate`, demais → `date`), calcula totais em single-pass
  - Chips de filtros ativos com remoção individual; `forkJoin` carrega contas + transações em paralelo no `ngOnInit`

- **Cadastro em sequência de transações (issue #50, 2026-06-08)**:
  - Botão "Salvar e lançar outra" no formulário de transação (modo criação, TRANSACTION apenas)
  - `doSave(): Observable<any>` extraído do `onSubmit()` — separa payload+HTTP de ação pós-save
  - `onSaveAndAddMore()`: salva via `doSave()`, exibe snackbar e chama `partialReset()` sem navegar
  - `partialReset()`: zera `description`, `amount`, `isInstallment`, `valueMode`, `propagateFields`; mantém `accountId`, `date`, `type`, `status`, `categoryId`; foca o campo description via `@ViewChild`
  - `reset(value)` no FormControl limpa estado `touched`/`dirty` (evita erros de validação imediatos no campo vazio)
  - `type="button"` obrigatório para não disparar `ngSubmit` ao clicar no botão secundário

- **Melhorias de UX no formulário de transação (issue #58, 2026-06-08)**:
  - **Reordenação de campos** no modo TRANSACTION: Descrição → Valor+Data → Conta → Classificação → Tipo → Status → Categoria (ordem lógica: o quê → quanto/quando → de onde → como classificar)
  - **Valor e Data na mesma linha** (`form-row-cols`, grid 50/50) — consistente com modo Transferência
  - **`R$` como prefixo inline** (`matTextPrefix`) no campo Valor (TRANSACTION e TRANSFER)
  - **"Cancelado" removido do cadastro**: status toggle exibe PENDING/PAID; CANCELLED só aparece quando `isEditMode() === true`
  - **Toggle de modo** renomeado de "Receita / Despesa" → "Transação" (o toggle alterna modo TRANSACTION/TRANSFER)
  - **Seção de propagação com destaque visual**: borda esquerda na cor primária do tema + fundo `surface-container-low` + ícone colorido

- **Templates de issue e PR (2026-06-08)**:
  - `.github/ISSUE_TEMPLATE/bug_report.md` — title "fix:", label: bug, assignee pré-configurado
  - `.github/ISSUE_TEMPLATE/feature_request.md` — title "feat:", label: enhancement
  - `.github/ISSUE_TEMPLATE/chore.md` — title "chore:", label: chore
  - `.github/ISSUE_TEMPLATE/config.yml` — menu de templates com `blank_issues_enabled: true`
  - `.github/pull_request_template.md` — checklist: testes, migrations aditivas, tenant isolation, sem `any`, sem `Co-Authored-By`

- **Bug fix: edição de conta não salvava dados do cartão (issue #61, 2026-06-09)**:
  - `AccountUpdateRequest` (spec) e `AccountUpdateDTO` (backend) não tinham `creditCardDetails` — PUT /api/accounts/{id} ignorava silenciosamente bandeira, dígitos, limite, fechamento e vencimento
  - Adicionado `creditCardDetails` ao `AccountUpdateRequest` no `openapi.yaml` e ao `AccountUpdateDTO.java`
  - `AccountService.update()` ganhou `upsertCreditCardDetails()`: busca existente com `findByAccount()` ou cria novo com builder, atualiza campos individualmente (null-safe)
  - Bug secundário corrigido: subscription de `type.valueChanges` sobrescrevia `countInLiquidBalance` ao carregar conta em edição — guard `if (isEditMode()) return` adicionado
  - Campo `type` desabilitado em edição (`disable({ emitEvent: false })`): tipo não pode mudar após criação; `getRawValue()` ainda inclui o valor para `isCreditCard()` funcionar
  - `onSubmit()` agora constrói payloads tipados separados: `AccountUpdateRequest` (sem `type`) em edição, `AccountCreateRequest` (com `type`) na criação — verificação em tempo de compilação via `satisfies`

- **Bug fix: botões não reativavam após "Salvar e lançar outra" (issue #63, 2026-06-09)**:
  - Causa raiz: `form.invalid` não é Signal — em Angular Zoneless, `setValue()` chamado programaticamente pode não disparar CD de forma confiável para atualizar `[disabled]`
  - `formStatusSignal = toSignal(form.statusChanges, { initialValue: form.status })` + `formValid = computed(() => formStatusSignal() === 'VALID')`: transforma validade do formulário em Signal reativo; qualquer mudança de `statusChanges` agenda tick automaticamente
  - `finalize(() => saving.set(false))` adicionado em `onSubmit()` e `onSaveAndAddMore()`: garante reset do signal `saving` mesmo se exceção ocorrer no callback `next`
  - Template atualizado: `[disabled]="!formValid() || saving()"` — ambos Signals, CD garantida

- **Bug fix: ícone de conta não gravava (issue #65, 2026-06-09)**:
  - Divergência: `selectedIconSignal` inicializado com `''` mas form `icon` inicializado com `'account_balance'`; corrigido alinhando `icon: ['account_balance']`

- **Bug fix: seção de propagação aparecia em transações não parceladas (issue #48, 2026-06-09)**:
  - `isPartOfInstallment = signal(false)` preenchido com `!!t.installmentGroupId` ao carregar transação em edição
  - Guard `@if (isEditMode() && isPartOfInstallment())` na seção de propagação

- **Bug fix: campo Limite sem máscara de moeda no formulário de conta (issue #53, 2026-06-09)**:
  - Campo `limitAmount` convertido de `type="number"` para `type="text"` com `matTextPrefix R$`
  - `limitDisplay = signal('')` + `onLimitInput/Blur/Focus` com a mesma lógica de currency mask do campo Valor

- **Melhorias nos filtros de transações e faturas (2026-06-09 — issues #52, #62, #64, #66)**:
  - **#52 — Auto-seleção de conta única em Faturas**: `InvoiceList.ngOnInit` faz `selectedId.set(cc[0].id)` quando `cc.length === 1` (sem preselect de URL); spec de testes atualizada para refletir o novo comportamento
  - **#66 — Filtro por descrição client-side**: campo de busca no painel de filtros; `filteredTransactions = computed()` aplica descrição sobre dados já em memória (sem round-trip); `description` nunca é persistida no localStorage (busca é pontual)
  - **#62 — Persistência dos filtros**: chave `fintech.transaction.filters`; spread sobre `DEFAULT_FILTERS` como fallback; `try/catch` em `saveToStorage` contra `QuotaExceededError`; `initialFilters` input em `TransactionFiltersComponent` para restaurar estado ao reabrir o painel
  - **#64 — Agrupar transações por fatura**: `groupByInvoice` toggle (mutuamente exclusivo com `groupByPeriod`); `buildDisplayRowsGroupedByInvoice` em `transaction-list.utils.ts`; row `invoice-header` com label, status chip (`InvoiceStatus`), total e contagem; flags client-side excluídos da comparação de reload para não disparar request HTTP desnecessário

- **Dataset de testes — Família Costa (2026-06-10)**:
  - `V10__seed_dev.sql` (Flyway perfil `dev`): tenant + 3 usuários (Carlos/Ana/Pedro + convite João) + 5 contas (Bradesco, Carteira, Nubank, Inter, XP) + 33 categorias + 100+ transações + 11 faturas + 2 grupos de parcelamento + transferências mensais
  - UUIDs predefinidos (série `10000000-...` a `70000000-...`) para cross-references sem query
  - Credenciais: `carlos@costa.com` / `costa123`; reset: `docker exec fintech-postgres psql -U admin -d postgres -c "DROP DATABASE fintech; CREATE DATABASE fintech;"`
  - `seed_base.sql` + `cleanup.sql` em `backend/src/test/resources/sql/` para Testcontainers (`admin@test.com` / `admin123`)
  - `docs/http/seed-dataset.http`: HTTP collection completa (9 blocos, IntelliJ/VS Code)
  - **Gotcha**: filtro de transações usa `accountIds` (plural) como query param — `accountId` (singular) é ignorado e retorna todos os dados do tenant

- **Melhorias de UX no formulário de transação (issue #69, #73, #60, #49 — 2026-06-11)**:
  - **#69 — Pares de transferência adjacentes**: `sortTransferPairsTogether()` em `transaction-list.utils.ts` — percorre a lista em ordem e puxar a perna par imediatamente após a primeira; sem mudança de template ou backend
  - **#73 — Filtro padrão = mês corrente**: `currentMonthFilters()` em `transaction-filters.types.ts`; `loadFromStorage()` usa `currentMonthFilters()` quando não há estado salvo; `clearFilters()` restaura mês corrente (não tabula rasa)
  - **#60 — Campo de data**: `↑`/`↓` incrementam/decrementam 1 dia; clicar no campo abre o datepicker; barras inseridas automaticamente na máscara `dd/mm/aaaa`
  - **#49 — Operações matemáticas no valor**: prefixo `=` ativa modo fórmula (ex: `=300+200` → `500,00`); `amount-math.ts` com parser recursivo descendente sem `eval`; suporta `+`, `-`, `*`, `/`, `^`, parênteses; 16 testes

- **Planejamento Mensal — Budget Cycles fullstack (issue #74 — 2026-06-12)** — PR #92 aberto:
  - Migration V12: `budget_cycles`, `budget_items`, `recurring_budget_items` + `budget_cycle_start_day` em `tenant`
  - `BudgetCycleService`: cálculo de datas do ciclo (startDay=1 → mês calendário; startDay=N → dia N do mês anterior até N-1 do mês atual); sincronização automática de parcelas de cartão ao abrir ciclo
  - `BudgetItemService`: criação, atualização, link/unlink para transações, guard anti-duplicação
  - `RecurringBudgetItemService`: CRUD de templates; `deactivate` faz soft-delete (`active=false`)
  - `TenantController` com `PATCH /api/tenant/settings`; 17 endpoints no OpenAPI spec
  - Frontend Angular 21 Zoneless: `BudgetCycleCurrentComponent`, `BudgetItemFormComponent`, `LinkTransactionDialogComponent`, `RecurringItemList`, `BudgetCycleList`, `BudgetCycleDetail`; rotas lazy `/planning/*`

- **Correções críticas de InvoiceService — ADR-001 (issues #83, #84 — 2026-06-12)** — PR #93:
  - **#83 — Race condition em `getOrCreate`**: extraído `createNewInvoice()` com `@Transactional(REQUIRES_NEW)` chamado via self-injection `@Lazy`. Conflito de chave única reverte apenas a transação interna; catch faz retry com `findBy` e retorna a fatura vencedora. Sem REQUIRES_NEW, a `DataIntegrityViolationException` marcaria a transação externa como rollback-only, impossibilitando o retry
  - **#84 — N+1 em `listDTOs`**: `findByAccountWithTotals` com `LEFT JOIN Transaction ON t.invoice = i GROUP BY i` — substitui `1 + 2N` queries por uma única query, independente do volume de faturas
  - **Fix Flyway em testes**: `src/test/resources/application-dev.properties` sobrescreve `spring.flyway.locations` para excluir `db/seed`. O arquivo de test-classpath substitui (não faz merge com) o de main-classpath — necessário replicar todas as props relevantes

**Próximos passos:**
- Issues médias do ADR-001: #85 (`effective_date`), #86 (`WITH RECURSIVE`), #87 (`TransferService`), #88 (`BusinessException`)
- Gráficos no dashboard (evolução mensal, breakdown por categoria/conta)
- Tela de Patrimônio Total — consome `countInNetWorth` (campo já existe em `accounts`)

---

## 📌 Resumo Operacional

Em cada interação significativa, o ciclo esperado é:

1. **Entender** o que foi pedido (e perguntar se houver ambiguidade)
2. **Planejar** explicitamente (com justificativas e conceitos envolvidos)
3. **Aguardar aprovação** do plano
4. **Executar** ensinando os conceitos aplicados
5. **Consolidar** com perguntas reflexivas ou oferta de aprofundamento

A meta não é só ter o software funcionando — é o desenvolvedor entender profundamente *por que* funciona.


## Available Skills

Skills are loaded from `.claude/skills/` (symlinked from claude-code-java).

To use a skill, load it first, then invoke with natural language:

### 1. Git Commit Messages
**Load**: `view .claude/skills/git-commit/SKILL.md`

**Use cases**:
- "Commit staged changes"
- "Create commit for bug fix #123"
- "Generate conventional commit message"

**Example**:
```
> view .claude/skills/git-commit/SKILL.md
> "Commit these changes"
→ fix(plugin-loader): prevent NPE when directory missing
```

### 2. Test Quality (JUnit 5 + AssertJ)
**Load**: `view .claude/skills/test-quality/SKILL.md`

**Use cases**:
- "Add tests for PluginManager.loadAll()"
- "Review existing tests in PluginLoaderTest"
- "Improve test coverage for lifecycle module"

**Example**:
```
> view .claude/skills/test-quality/SKILL.md
> "Add unit tests for ExtensionFactory with edge cases"
→ Generates JUnit 5 tests with AssertJ assertions
```

### 3. Issue Triage
**Load**: `view .claude/skills/issue-triage/SKILL.md`

**Use cases**:
- "Triage the last 10 issues"
- "Check recent bug reports"
- "Prioritize open feature requests"

**Example**:
```
> view .claude/skills/issue-triage/SKILL.md
> "Triage issues from fintech-core, last 15"
→ Categorizes, labels, suggests responses
```

## MCP Servers (Optional)

MCP servers enhance capabilities with structured, token-efficient operations:

| Server | Benefits |
|--------|----------|
| GitHub MCP | Issue management, PR creation |
| Filesystem MCP | Structured file tree navigation |
| Git MCP | Commit history, blame, log parsing |

To configure MCP servers, run from claude-code-java:
```bash
./scripts/configure-mcp.sh /path/to/this/project
```

See [MCP documentation](https://modelcontextprotocol.io/) for details.

## Common Workflows

### Daily Development Flow
```bash
# 1. Start session
claude code .

# 2. Work on feature/fix
# ... make code changes ...

# 3. Add tests (load test-quality skill)
> view .claude/skills/test-quality/SKILL.md
> "Add tests for new functionality in class X"

# 4. Commit (load git-commit skill)
> view .claude/skills/git-commit/SKILL.md
> "Commit staged changes"

# 5. Push and create PR
> "Push changes and create PR for issue #123"
```

### Weekly Maintenance
```bash
# Monday morning: Issue triage
claude code .

> view .claude/skills/issue-triage/SKILL.md
> "Triage the last 20 issues, categorize and prioritize"

# Review suggested actions
> "Apply labels and post responses as suggested"
```

### Code Review
```bash
# Review PR
> "Review PR #456 focusing on:
   - Test coverage (use test-quality skill)
   - Commit message quality (use git-commit skill)
   - Code patterns and best practices"
```

## Token Budget Guidelines

To optimize token usage:

1. **Load skills once per session** - Skills stay in context
2. **Batch operations** - Process multiple issues/tests together
3. **Use MCP when available** - More efficient than bash commands
4. **Targeted file reads** - Only read files you need

### Target Token Usage

| Task | Without Skills | With Skills | Savings |
|------|----------------|-------------|---------|
| Commit message | ~800 tokens | ~300 tokens | 62% |
| Add 3 tests | ~2000 tokens | ~800 tokens | 60% |
| Triage 10 issues | ~5000 tokens | ~2000 tokens | 60% |

## What to Avoid

1. **Don't reload skills repeatedly** - Load once per session
2. **Don't process issues one-by-one** - Batch them
3. **Don't over-engineer** - Use skills for appropriate tasks
4. **Don't ignore skill guidelines** - They're optimized for tokens

## Project-Specific Notes

### Build Commands
```bash
# Maven
mvn clean install
mvn test
mvn jacoco:report

# Check test coverage
open target/site/jacoco/index.html
```

### Testing Strategy
- Target: 80%+ coverage on core logic
- Focus: Business logic, not boilerplate
- Tools: JUnit 5, AssertJ, Mockito

### Commit Guidelines
- Follow Conventional Commits
- Reference issues: "Fixes #123"
- Keep subject under 50 chars

### Issue Management
- Label all new issues within 48h
- Respond to questions within 1 week
- Close stale (>90 days, no activity) issues

## Resources

- [claude-code-java](https://github.com/decebals/claude-code-java) - Skill repository
- [Claude Code Docs](https://code.claude.com/docs) - Official documentation
- [Conventional Commits](https://www.conventionalcommits.org/) - Commit format
- [AssertJ Docs](https://assertj.github.io/doc/) - Assertion library

## Tips & Tricks

### Quick skill loading
```bash
# Add to your shell alias
alias cc-commit='echo "view .claude/skills/git-commit/SKILL.md"'
alias cc-test='echo "view .claude/skills/test-quality/SKILL.md"'
alias cc-triage='echo "view .claude/skills/issue-triage/SKILL.md"'
```

### Session continuity
```bash
# Save context at end of session
> "Summarize what we worked on today for next session"

# Resume next day
> "Review yesterday's summary and continue"
```

### Measure your wins
```bash
# Track token usage
> /token usage

# Compare before/after adopting skills
# Document savings in team retrospectives
```

---

**Last updated**: 2026-05-19
**claude-code-java version**: v0.1
