# Design: Gerenciamento de Equipe e Convites (Issue #24)

**Data:** 2026-06-05
**Status:** Aprovado
**Issue:** [#24](https://github.com/sergiohcosta/fintech-core/issues/24)

---

## Resumo

Implementar a tela `/team` dentro do shell autenticado, permitindo que o admin do tenant visualize membros ativos, gerencie convites pendentes e envie novos convites por link (email será suportado em versão futura).

---

## Escopo

### Incluído
- `GET /invites` — lista convites do tenant com status calculado
- `DELETE /invites/{id}` — revoga convite pendente
- `GET /api/members` — lista usuários do tenant
- Página `/team` com duas seções: Membros e Convites
- Dialog de criação de convite com exibição de link e cópia para clipboard
- Visibilidade de ações restritas por role (ADMIN)

### Excluído
- Envio de e-mail (previsto para futura iteração)
- Edição de role de membros
- Remoção de membros

---

## Backend

### Novos endpoints

| Método | Rota | Auth | Descrição |
|--------|------|------|-----------|
| GET | `/invites` | ADMIN | Lista todos os convites do tenant |
| DELETE | `/invites/{id}` | ADMIN | Revoga convite pendente |
| GET | `/api/members` | autenticado | Lista usuários do tenant |

### DTOs

**`InvitationSummaryDTO`** (novo):
```java
record InvitationSummaryDTO(
    UUID id,
    String email,
    InvitationStatus status,   // enum: PENDING, ACCEPTED, EXPIRED
    LocalDateTime createdAt,
    LocalDateTime expiresAt,
    String link                // null se ACCEPTED ou EXPIRED
)
```

**`MemberDTO`** (novo):
```java
record MemberDTO(
    UUID id,
    String name,
    String email,
    UserRole role
)
```

### Enum `InvitationStatus` (novo)

Calculado em serviço — não armazenado no banco:
- `ACCEPTED` → `used = true`
- `EXPIRED` → `used = false` && `expiresAt <= now`
- `PENDING` → `used = false` && `expiresAt > now`

### Alterações em classes existentes

**`InvitationRepository`** — novo método:
```java
List<Invitation> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
```

**`InvitationService`** — novos métodos:
- `list(User admin)` → `List<InvitationSummaryDTO>`
- `revoke(UUID id, User admin)` → void; lança `BusinessConflictException` se `used=true` ou se convite não pertence ao tenant do admin

**`InvitationController`** — novos handlers:
- `GET /invites` → chama `list()`
- `DELETE /invites/{id}` → chama `revoke()`

**`UserRepository`** — novo método:
```java
List<User> findAllByTenantIdOrderByNameAsc(UUID tenantId);
```

**Novos arquivos:**
- `MembersController` — `GET /api/members`
- `MembersService` — delega para `UserRepository`

### Segurança

Sem nova migration. `DELETE /invites/{id}` remove o registro físico do banco (sem soft delete). Tentativa de revogar convite aceito (`used=true`) retorna 409. Convites expirados podem ser deletados (operação de limpeza). Tentativa de revogar convite de outro tenant retorna 404 (nunca vazar existência de dados de outro tenant).

**`SecurityConfigurations.java` — linhas a adicionar** (após as regras de `/invites` existentes):
```java
.requestMatchers(HttpMethod.GET, "/invites").hasRole("ADMIN")
.requestMatchers(HttpMethod.DELETE, "/invites/*").hasRole("ADMIN")
// GET /api/members já cai em anyRequest → authenticated — nenhuma regra extra necessária
```

O padrão `GET /invites/*` já existente (público) cobre apenas `/invites/{token}` — não conflita com `GET /invites` (sem segmento extra).

---

## Frontend

### Estrutura de arquivos (novos)

```
features/team/
  team/
    team.ts
    team.html
    team.scss
  invite-dialog/
    invite-dialog.ts
    invite-dialog.html
```

### Rota

Adicionada ao bloco de filhos do `ShellComponent` em `app.routes.ts`:
```typescript
{
  path: 'team',
  loadComponent: () => import('./features/team/team/team').then(m => m.TeamComponent)
}
```

### Layout da página `/team`

```
┌─────────────────────────────────────┐
│  Equipe                [+ Convidar] │  ← botão visível apenas para ADMIN
├─────────────────────────────────────┤
│  Membros (N)                        │
│  ┌──────────────────────────────┐   │
│  │ Nome    Email       Papel    │   │
│  └──────────────────────────────┘   │
│                                     │
│  Convites (N)                       │
│  ┌──────────────────────────────┐   │
│  │ Email    Status    Expira [🗑]│   │  ← ícone de revogar apenas PENDING + ADMIN
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
```

### Dialog "Convidar membro"

Dois estados internos controlados por `signal<'form' | 'success'>`:

**Estado `form`:**
- Campo email (required, formato email)
- Botões: Cancelar / Convidar

**Estado `success`:**
- Mensagem de confirmação
- Link do convite exibido em campo readonly
- Botão "Copiar link" → `navigator.clipboard.writeText()` → feedback "Copiado!" por 2s
- Botão "Fechar"

O dialog não fecha automaticamente após copiar.

### Serviços

**`InvitationService`** — novos métodos:
```typescript
list(): Observable<InvitationSummary[]>
revoke(id: string): Observable<void>
```

**`MembersService`** (novo):
```typescript
list(): Observable<Member[]>
```

**Interfaces TypeScript:**
```typescript
interface InvitationSummary {
  id: string;
  email: string;
  status: 'PENDING' | 'ACCEPTED' | 'EXPIRED';
  createdAt: string;
  expiresAt: string | null;
  link: string | null;
}

interface Member {
  id: string;
  name: string;
  email: string;
  role: 'ADMIN' | 'USER';
}
```

### Controle de role no frontend

`AuthService` já decodifica o JWT. Expor `isAdmin(): boolean` (ou `computed`) para condicionar visibilidade do botão "Convidar" e do ícone de revogar via `@if`.

### Navegação

Adicionar item "Equipe" no sidenav do `ShellComponent`.

---

## Tratamento de erros

| Cenário | Comportamento |
|---|---|
| Email já tem conta ou convite ativo | 409 → snackbar no dialog: mensagem do backend |
| Revogar convite aceito ou expirado | 409 → snackbar na página |
| Erro de rede ao carregar | Mensagem inline com botão "Tentar novamente" |
| Role USER acessa `/team` | Página carrega, ações de escrita ocultas via `@if` |

---

## Testes

### Backend

- `InvitationServiceTest`
  - `list()` retorna apenas convites do tenant do admin autenticado
  - `revoke()` lança `BusinessConflictException` para convite já aceito
  - `revoke()` lança `EntityNotFoundException` para convite de outro tenant
  - Status calculado corretamente (PENDING / ACCEPTED / EXPIRED)

- `MembersServiceTest` (novo)
  - `list()` retorna apenas usuários do tenant

### Frontend (Vitest)

- `TeamComponent`
  - Renderiza lista de membros
  - Renderiza lista de convites com chips de status
  - Oculta botão "Convidar" para role USER
  - Oculta ícone de revogar para role USER

- `InviteDialogComponent`
  - Transição form → success ao criar convite com sucesso
  - Chama `navigator.clipboard.writeText` ao clicar em "Copiar link"
  - Exibe erro via snackbar em caso de 409

---

## Fluxo completo (happy path)

```
Admin abre /team
  → TeamComponent carrega membros e convites em paralelo
  → Clica "+ Convidar"
  → InviteDialogComponent abre no estado "form"
  → Admin digita email e confirma
  → POST /invites → backend cria convite
  → Dialog transiciona para estado "success" com link
  → Admin copia link → envia manualmente por mensagem
  → Futuro: backend envia e-mail automaticamente
```
