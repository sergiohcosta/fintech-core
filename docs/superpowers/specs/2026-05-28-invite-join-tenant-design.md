# Design: Convite para Ingresso em Tenant Existente

**Data:** 2026-05-28
**Status:** Aprovado
**Tipo:** Feature

---

## Contexto

Hoje o único fluxo de cadastro existente (`POST /auth/register`) cria um **novo Tenant + Usuário ADMIN** atomicamente. Não há como um usuário ingressar em um tenant já existente.

Esta feature introduz um sistema de convites single-use, vinculados a email, gerados pelo ADMIN do tenant.

---

## Decisões

| Decisão | Escolha |
|---|---|
| Mecanismo de ingresso | Convite por link/token |
| Quem gera o convite | ADMIN do tenant |
| Email no convite | Vinculado ao email do convidado |
| Uso do token | Single-use |
| Aprovação pós-aceite | Imediata (sem aprovação manual) |
| Envio de email | Fora do escopo — feature futura |
| Role do usuário criado | USER |

---

## Fluxo Geral

```
Admin                     Backend                    Convidado
  │                           │                           │
  │  POST /invites             │                           │
  │  { email: "x@y.com" }     │                           │
  │──────────────────────────►│                           │
  │                           │ cria Invitation            │
  │  { token, link, expires } │ (pending, email, tenant)  │
  │◄──────────────────────────│                           │
  │                           │                           │
  │  [copia e envia o link]   │                           │
  │──────────────────────────────────────────────────────►│
  │                           │                           │
  │                           │  GET /invites/{token}     │
  │                           │◄──────────────────────────│
  │                           │  { email, tenantName }    │
  │                           │──────────────────────────►│
  │                           │                           │
  │                           │  POST /auth/accept-invite │
  │                           │  { token, name, password }│
  │                           │◄──────────────────────────│
  │                           │ valida token              │
  │                           │ cria User (role=USER)     │
  │                           │ marca invitation como used│
  │                           │  { jwt_token }            │
  │                           │──────────────────────────►│
  │                           │                   (logado)│
```

---

## Modelo de Dados

### Nova tabela: `invitations` (migration V6)

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | UUID PK | gerado pelo banco |
| `tenant_id` | UUID FK | referencia `tenants(id)` |
| `email` | VARCHAR(255) | email do convidado |
| `token` | VARCHAR(255) UNIQUE | UUID aleatório seguro |
| `expires_at` | TIMESTAMP WITH TIME ZONE | padrão: agora + 7 dias |
| `used` | BOOLEAN | começa `false`, vira `true` no aceite |
| `created_at` | TIMESTAMP WITH TIME ZONE | |

---

## Backend

### Novos arquivos

```
backend/src/main/java/com/fintech/api/
  domain/invitation/
    Invitation.java

  dto/
    CreateInvitationDTO.java      ← { email }
    InvitationResponseDTO.java    ← { token, link, email, expiresAt }
    InvitationInfoDTO.java        ← { email, tenantName } (GET público)
    AcceptInviteDTO.java          ← { token, name, password }

  repository/
    InvitationRepository.java

  service/
    InvitationService.java

  controller/
    InvitationController.java

backend/src/main/resources/db/migration/
  V6__create_invitations.sql

AuthController.java               ← + POST /auth/accept-invite
```

### Endpoints

| Método | Rota | Auth | Descrição |
|---|---|---|---|
| POST | `/invites` | ADMIN | Gera convite para email |
| GET | `/invites/{token}` | Pública | Valida token, retorna email + tenantName |
| POST | `/auth/accept-invite` | Pública | Aceita convite, cria usuário, retorna JWT |

### Regras de negócio (`InvitationService`)

**Criar convite:**
- Apenas ADMIN do próprio tenant pode gerar
- Email não pode já existir em `users`
- Email não pode ter convite pendente (não expirado) para o mesmo tenant
- Token: `UUID.randomUUID().toString()`
- Expiração: `LocalDateTime.now().plusDays(7)`

**Validar (GET):**
- Token existe
- `used = false`
- `expires_at` no futuro
- Retorna `{ email, tenantName }`

**Aceitar:**
- Mesmas validações do GET
- Email informado no corpo deve bater com o email do convite
- Cria `User` com `role = USER`, vinculado ao `tenant_id` do convite
- Marca `used = true`
- Tudo em `@Transactional`
- Retorna JWT (auto-login)

---

## Frontend

### Nova rota

```
/accept-invite?token=xxx   ← pública, lazy-loaded
```

### Novo componente: `AcceptInviteComponent`

**Estados:**

| Estado | Descrição |
|---|---|
| Carregando | Spinner enquanto valida token via `GET /invites/{token}` |
| Erro | Mensagem descritiva (token inválido, expirado, já usado) |
| Formulário | Email pré-preenchido (readonly) + Nome + Senha |
| Sucesso | Salva JWT e redireciona para `/dashboard` |

### Novos arquivos

```
frontend/src/app/
  core/services/
    invitation.ts                 ← InvitationService

  features/auth/
    accept-invite/
      accept-invite.ts
      accept-invite.html
      accept-invite.scss
      accept-invite.spec.ts

  app.routes.ts                   ← + rota /accept-invite (lazy)
```

### Comportamento de segurança

- Usuário autenticado que acessa `/accept-invite` **não é redirecionado** — pode querer aceitar convite legitimamente
- Email no formulário é `readonly` — validação real ocorre no backend

---

## Tratamento de Erros

| Situação | HTTP | Mensagem |
|---|---|---|
| Token não encontrado | 404 | "Convite inválido ou inexistente" |
| Token já utilizado | 410 | "Este convite já foi utilizado" |
| Token expirado | 410 | "Este convite expirou" |
| Email não bate com o token | 400 | "Este convite não pertence a este email" |
| Email já cadastrado | 409 | "Este email já possui uma conta" |
| Convite pendente já existe | 409 | "Já existe um convite pendente para este email" |
| ADMIN tenta convidar de outro tenant | 403 | tratado pelo SecurityFilter |

Exceções específicas a criar: `InviteAlreadyUsedException`, `InviteExpiredException` — capturadas pelo `GlobalExceptionHandler` existente.

---

## Testes

### Backend (JUnit 5 + Mockito)

- `InvitationService`: criar convite — happy path, email duplicado em users, convite pendente existente
- `InvitationService`: aceitar convite — happy path, token expirado, token já usado, email errado
- `InvitationController`: MockMvc — autenticação ADMIN obrigatória, 403 para USER

### Frontend (Vitest)

- `AcceptInviteComponent`: renderiza spinner → formulário → sucesso
- `AcceptInviteComponent`: exibe erro quando token inválido/expirado
- `InvitationService`: chamadas HTTP corretas

---

## Fora do Escopo (features futuras)

- Envio automático de email ao gerar convite
- Listagem de convites pendentes na UI de admin
- Cancelamento de convites pela UI
- Convites com múltiplos usos
