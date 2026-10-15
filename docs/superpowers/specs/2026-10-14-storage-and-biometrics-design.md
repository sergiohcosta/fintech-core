# Design: Storage seguro + biometria (Fase 7 — mobile-friendly)

**Data:** 2026-10-14
**Escopo:** Frontend only — `TokenStorageService` (IndexedDB + AES-GCM) + `BiometricAuthService` (Credentials API) + migração de JWT do localStorage
**Fase do roadmap:** Slice 7 de 7 (última fase do mobile-friendly)

---

## Contexto e motivação

`AuthService` guarda o JWT em `localStorage` com chave `auth_token` (per explorer). Isso é vulnerável a XSS — qualquer script injetado consegue roubar o token. E a chave está **duplicada como string literal** em `auth.interceptor.ts:4` (não usa o `AuthService`), criando risco de divergência.

Além disso, mobile-first exige **re-auth prática**: o usuário mobile não quer digitar email+senha toda vez que abre o app. Biometria (Touch ID / Face ID / impressão digital) resolve isso sem reduzir segurança.

Esse design migra o storage pra IndexedDB criptografado (AES-GCM via Web Crypto API), remove a duplicação do literal, e adiciona biometria pragmática usando **Credentials API** (não WebAuthn server-side — escopo maior; ver Risks).

---

## Decisões já tomadas (resumo do grill pré-execução)

| # | Decisão | Origem |
|---|---|---|
| Biometria pragmática | Credentials API + token JWT atual (não WebAuthn real) | Q9 |
| Storage | IndexedDB com AES-GCM (Web Crypto API) | Q9 |
| Fallback | `sessionStorage` (não `localStorage`) se IndexedDB/Crypto indisponível | melhor prática |
| Sem libs novas | Web Crypto API + IndexedDB nativos | padrão do projeto |
| HTTPS | Prod (Netlify) já tem; dev usa `localhost` (exempted) | Q17 |

---

## Mudanças concretas

### 1. `TokenStorageService` novo

`frontend/src/app/core/services/token-storage.service.ts` — serviço único de storage do JWT.

```ts
@Injectable({ providedIn: 'root' })
export class TokenStorageService {
  private readonly dbName = 'fintech-core-auth';
  private readonly storeName = 'tokens';
  private readonly dbVersion = 1;

  // Cache em memória para interceptor síncrono
  readonly token = signal<string | null>(null);
  readonly biometricEnabled = signal(false);

  async init(): Promise<void> {
    // Abre IndexedDB, lê token, popula cache em memória
    // Migra token antigo de localStorage se existir
  }

  async saveToken(token: string): Promise<void> {
    // Criptografa com AES-GCM, salva em IndexedDB
    // Atualiza cache em memória
  }

  async clearToken(): Promise<void> {
    // Remove do IndexedDB, limpa cache
  }

  async enableBiometric(): Promise<void> {
    // Salva token + flag biometricEnabled
  }

  async disableBiometric(): Promise<void> {
    // Remove flag (mantém token em IndexedDB pra login normal)
  }
}
```

**Criptografia (AES-GCM)**:
- Chave mestra: gerada via `crypto.subtle.generateKey({name: 'AES-GCM', length: 256}, true, ['encrypt', 'decrypt'])`
- Chave exportada e persistida em IndexedDB (object store separada: `keys`)
- IV: 12 bytes random por operação (`crypto.getRandomValues`)
- Envelope: `base64(iv).base64(ciphertext)`

### 2. `BiometricAuthService` novo

`frontend/src/app/core/services/biometric-auth.service.ts` — usa Credentials API:

```ts
@Injectable({ providedIn: 'root' })
export class BiometricAuthService {
  readonly isAvailable = signal(false);

  async checkAvailability(): Promise<void> {
    // navigator.credentials && window.PublicKeyCredential
  }

  async enableForCurrentUser(token: string): Promise<void> {
    // Cria credential com CredentialsContainer.store()
    // Salva token no TokenStorageService com flag biometricEnabled
  }

  async authenticate(): Promise<boolean> {
    // navigator.credentials.get({ password: true, mediation: 'required' })
    // Se sucesso, lê token do TokenStorageService
  }
}
```

**Pragmatismo**: usamos `CredentialsContainer.store` com `id = user.email` e `password = token` (a "credencial" é o próprio JWT). Não é WebAuthn real (chave assimétrica server-side), mas é o que o scope permite. Upgrade pra WebAuthn real é uma fase futura (ver Risks).

### 3. Refactor `AuthService`

`frontend/src/app/core/services/auth.ts`:
- Injetar `TokenStorageService` e `BiometricAuthService`
- `login(email, password)`: chama backend, salva via `TokenStorageService.saveToken()` (não mais `localStorage.setItem`)
- `logout()`: chama `TokenStorageService.clearToken()` (não mais `localStorage.removeItem`)
- Signal `currentUser` continua igual
- `bootstrap()` async: chama `TokenStorageService.init()` no startup, lê token antigo de localStorage, migra transparentemente

### 4. Corrigir `auth.interceptor.ts:4`

Substituir `localStorage.getItem('auth_token')` por leitura do **cache em memória** do `TokenStorageService`:

```ts
// antes
const token = localStorage.getItem('auth_token');

// depois
const token = inject(TokenStorageService).token();  // signal, síncrono
```

Isso elimina a duplicação do literal. Interceptor volta a ser síncrono.

### 5. UI: tela de login

Adicionar botão **"Entrar com biometria"** (`<button mat-stroked-button>`) abaixo do form de login:
- Visível só se `BiometricAuthService.isAvailable() && TokenStorageService.biometricEnabled()`
- Ao tocar: chama `BiometricAuthService.authenticate()`; se sucesso, popula `currentUser` e navega pra `/dashboard`
- Estado disabled com spinner enquanto autentica

### 6. UI: tela de perfil/equipe

Adicionar toggle **"Ativar login com biometria neste device"** (`<mat-slide-toggle>`):
- Em `/team` ou nova rota `/profile/settings`
- Ao ligar: `BiometricAuthService.enableForCurrentUser(token)`; snackbar "Biometria ativada"
- Ao desligar: `BiometricAuthService.disableBiometric()`; snackbar "Biometria desativada"

### 7. Bootstrap da app

`app.config.ts` (ou `main.ts`):
- Chamar `TokenStorageService.init()` no startup (depois do `provideRouter`)
- Se houver token migrado, `AuthService.currentUser` já reflete o usuário logado
- Caso contrário, redireciona pra `/login`

---

## Arquivos a criar/alterar

| Arquivo | Tipo | Mudança |
|---|---|---|
| `frontend/src/app/core/services/token-storage.service.ts` | novo | Service de storage com crypto |
| `frontend/src/app/core/services/biometric-auth.service.ts` | novo | Service de biometria |
| `frontend/src/app/core/services/auth.ts` | edit | Migrar de localStorage pra TokenStorageService |
| `frontend/src/app/core/interceptors/auth.interceptor.ts` | edit | Usar cache do TokenStorageService (não localStorage) |
| `frontend/src/app/features/auth/login/login.ts` | edit | Adicionar botão "Entrar com biometria" |
| `frontend/src/app/features/auth/login/login.html` | edit | Botão de biometria condicional |
| `frontend/src/app/features/team/team.ts` ou nova rota de perfil | edit | Adicionar toggle de biometria |
| `frontend/src/app/app.config.ts` (ou `main.ts`) | edit | Bootstrap `TokenStorageService.init()` |

Total: 2 novos, 6 edits.

---

## Critérios de aceite

### Funcionais

- [ ] Login com email+senha funciona; token vai pra IndexedDB criptografado, não mais localStorage
- [ ] Cold start com token em IndexedDB: usuário entra direto no dashboard (sem re-login)
- [ ] Cold start com token em localStorage (legado): migra transparentemente pra IndexedDB, depois apaga do localStorage
- [ ] Botão "Entrar com biometria" aparece em `/login` se biometria foi ativada
- [ ] Toggle de biometria em perfil/equipe ativa/desativa corretamente
- [ ] Biometria funciona em iOS Safari (Face ID/Touch ID) e Android Chrome (fingerprint/face)
- [ ] Logout limpa token do IndexedDB e do cache
- [ ] Interceptor usa cache em memória, não localStorage (corrige duplicação do literal)

### Não-funcionais

- [ ] Build verde (`npm run build`)
- [ ] 375+ testes Vitest verde
- [ ] Sem `<any>` TypeScript
- [ ] Sem `console.log` (logging do tipo `[TokenStorage] init` é aceitável em dev, mas remover antes de prod)
- [ ] Sem `Co-Authored-By`
- [ ] HTTPS no dev verificado: `ng serve --ssl` se necessário; `localhost` exempted
- [ ] Comentário pedagógico nos services novos explicando o "por que" da decisão pragmática

### Validação manual

- [ ] Login → logout → re-login: token vai pra IndexedDB, sai do IndexedDB
- [ ] Em dev (localhost), biometria mockada: o Chrome DevTools suporta `navigator.credentials` (vê em chrome://flags/#web-authentication)
- [ ] Em device real (iOS Safari), Touch ID funciona após toggle
- [ ] Inspecionar IndexedDB: `DevTools → Application → IndexedDB → fintech-core-auth` — tem o token criptografado
- [ ] Inspecionar localStorage: **vazio** (sem chave `auth_token`)

---

## Riscos abertos

1. **Web Crypto API requer HTTPS em prod**: em prod (Netlify) já é HTTPS. Em dev (`ng serve`), `localhost` é exempted. Se alguém rodar em dev com IP da LAN, vai falhar silenciosamente. **Mitigação**: service tenta, se falhar, usa `sessionStorage` (não `localStorage`) com warning visível em dev.
2. **Credentials API não é WebAuthn real**: a "credencial" armazenada é o próprio JWT, não uma chave assimétrica. Se o device for comprometido, o token está em risco (igual ao localStorage original). **Mitigação**: WebAuthn real é uma fase futura — Fase 7.5 ou Issue dedicada.
3. **Migração transparent pode falhar**: se IndexedDB bloquear no boot, app não carrega. **Mitigação**: try/catch em `init()` com fallback.
4. **Logout em device compartilhado**: usuário A ativa biometria, usuário B abre o app — B consegue entrar como A via biometria? Não — biometria requer a digital/face do user, e ela é per-user no device. Mas se o device for compartilhado, é compartilhamento do device, não falha da feature.
5. **`sessionStorage` fallback**: ainda expõe o token em XSS, mas é melhor que `localStorage` (some ao fechar a aba). Aceitável como degraded mode.
6. **Sem HTTPS no dev** se usuário rodar em `192.168.x.x` em vez de `localhost`: Web Crypto e Credentials API podem falhar. Documentar.

---

## Out of scope

| Tema | Razão |
|---|---|
| WebAuthn real (chave assimétrica server-side) | Fase futura; requer mudanças no backend (`/auth/webauthn/register`, `/auth/webauthn/authenticate`) |
| Fila de escritas offline (retry de POSTs) | Combinaria com Fase 3 (SW), mas é uma Fase 8 dedicada |
| Refresh token rotation | Backend primeiro; UI é trivial depois |
| Device fingerprinting robusto | Web Crypto + `crypto.getRandomValues` é suficiente por enquanto |

---

## Notas de implementação pro @designer

- Caminho da worktree: `/home/sergio/projetos/fintech-core/.worktrees/storage-and-biometrics`
- Use `git -C ...` em todos os comandos git
- Você tem contexto denso de transactions/planning (do trabalho das Fases 4-6) — mas pra auth vai precisar ler `frontend/src/app/core/services/auth.ts` (121 linhas) e `frontend/src/app/core/interceptors/auth.interceptor.ts` (paths NOVOS!)
- 2-3 commits separados: `feat(auth): migra token para IndexedDB criptografado`, `feat(auth): adiciona login com biometria via Credentials API`, `feat(login): adiciona botao de biometria e toggle em perfil`
- Mensagens PT-BR imperativas, sem `Co-Authored-By`
- Build verde (`npm run build`) + 375+ testes Vitest verde (`npm test -- --watch=false`, NUNCA `npx vitest` direto)
- **Atenção**: Web Crypto API é assíncrona (Promise). IndexedDB também. `init()` é async — bootstrap precisa aguardar. Cuidado com race conditions.