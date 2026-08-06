# App Android — Seletor de Ambiente (Local/Dev/Hmg/Prod + LAN/Tailscale) — Design

## Contexto

O app Android de lançamento de transações (v1, `docs/superpowers/plans/2026-07-31-android-lancamento-transacoes.md`) hoje aponta sempre para `http://10.0.2.2:8080/`, hardcoded em `NetworkModule.kt` — o alias que só existe no emulador Android para alcançar o `localhost` da máquina host. Isso restringe o app a rodar só no emulador, contra um backend local.

Para uso em dispositivo físico contra os ambientes reais do homelab (dev/hmg/prod, `homelab-k8s`), o app precisa de um seletor de ambiente. Cada ambiente remoto é alcançável por duas rotas de rede distintas, dependendo de onde o dispositivo está fisicamente:

- **LAN**: dentro da rede de casa, via DNS local (`.kafofao`), HTTP puro, porta 80.
- **Tailscale**: de qualquer lugar, desde que o app Tailscale do Android esteja com a VPN ativa, via MagicDNS (`.atlas-haddock.ts.net`), HTTPS com TLS automático (Let's Encrypt).

Confirmado em `homelab-k8s/projects/fintech-core/overlays/{dev,hmg,prod}/{ingress.yaml,ingress-lan.yaml,configmap.yaml}`:

| Ambiente | LAN | Tailscale |
|---|---|---|
| Dev | `http://fintech-core-dev.kafofao/` | `https://fintech-core-dev.atlas-haddock.ts.net/` |
| Hmg | `http://fintech-core-hmg.kafofao/` | `https://fintech-core-hmg.atlas-haddock.ts.net/` |
| Prod | `http://fintech-core.kafofao/` | `https://fintech-core.atlas-haddock.ts.net/` |

Em ambos os casos, o host aponta para o **frontend** (`fintech-core-frontend`), cujo Nginx (`frontend/nginx.conf`) faz proxy reverso de `/api/` e `/auth/` para o backend — os paths consumidos pelo Retrofit (`/auth/login`, `/api/transactions`, ...) **não mudam**, só o host/scheme base.

`Local` continua sem ingress fixo — cobre tanto o emulador (`10.0.2.2:8080`, direto no backend, sem proxy) quanto um dispositivo físico na mesma rede da máquina de dev, via URL customizável digitada pelo usuário (ex.: `http://192.168.1.50:8080/`).

O app **não liga/desliga a VPN Tailscale** — isso é responsabilidade do app Tailscale do Android, controlado pelo usuário separadamente. Escolher a rota "Tailscale" sem a VPN ativa (ou "LAN" fora de casa) simplesmente falha como qualquer erro de rede — mesmo caminho de `NetworkError` que já existe.

## Decisões

**a) Onde selecionar:** na `LoginScreen`, antes de autenticar. Trocar de ambiente não afeta nenhuma sessão porque ainda não há login. Trocar de ambiente **depois** de logado não é suportado nesta v1 (não há botão de logout no app hoje) — pré-requisito de uma v2 futura.

**b) Arquitetura de rede:** um `OkHttp Interceptor` (`EnvironmentInterceptor`) reescreve host/scheme/porta de cada requisição em runtime, lendo a seleção atual de `EnvironmentPreferences`. O `Retrofit`/`OkHttpClient` continuam Hilt-singletons como hoje — nenhuma reconstrução do grafo de DI ao trocar de ambiente. É o padrão usual para "base URL dinâmica" com Retrofit.

**c) Persistência:** `EnvironmentPreferences` — `SharedPreferences` simples (não criptografado; não é dado sensível), guarda `Environment`, `NetworkRoute` e `customLocalUrl`. Separado do `SessionManager` (que continua `EncryptedSharedPreferences`, só para o JWT).

**d) Cleartext HTTP:** liberado globalmente (`base-config cleartextTrafficPermitted="true"` em `network_security_config.xml`, substituindo a exceção pontual atual só para `10.0.2.2`). Justificativa: o app não tem build de release/distribuição nesta v1 (achado do review final da branch anterior) — é ferramenta de uso pessoal/dev, e a URL customizável de `Local` em dispositivo físico não pode ser whitelisted em compile-time.

## Componentes

**Novos:**
- `data/EnvironmentPreferences.kt` — persiste `Environment { LOCAL, DEV, HMG, PROD }`, `NetworkRoute { LAN, TAILSCALE }` (irrelevante para `LOCAL`) e `customLocalUrl: String?`
- `core/network/EnvironmentUrlResolver.kt` — função pura `resolveBaseUrl(env, route, customLocalUrl): String`, tabela estática dos 6 hosts conhecidos + fallback pro campo customizável em `LOCAL`
- `session/EnvironmentInterceptor.kt` — `Interceptor` que reescreve a URL de cada request via `EnvironmentPreferences` + `EnvironmentUrlResolver`, registrado no `OkHttpClient` do `NetworkModule` (antes do `AuthInterceptor`)
- `ui/login/EnvironmentSelector.kt` — Composable: dropdown de ambiente (Local/Dev/Hmg/Prod) + toggle segmentado LAN/Tailscale (visível só quando ambiente ≠ Local) + campo de texto (visível só quando ambiente = Local)

**Modificados:**
- `NetworkModule.kt` — remove `BASE_URL` fixa; `Retrofit.baseUrl(...)` passa a usar qualquer placeholder válido (a URL real é decidida pelo `EnvironmentInterceptor`); adiciona `EnvironmentInterceptor` na cadeia
- `LoginScreen.kt`/`LoginViewModel.kt`/`LoginUiState.kt` — incorporam o `EnvironmentSelector` e leem/gravam `EnvironmentPreferences`
- `AndroidManifest.xml`/`network_security_config.xml` — cleartext liberado globalmente

## Fluxo de Dados

```
App abre → LoginScreen lê EnvironmentPreferences (default: LOCAL, sem customUrl)
         → usuário troca ambiente/rota/URL customizável (grava a cada mudança)
         → usuário toca Entrar
         → AuthRepository.login() → AuthApi.login() (Retrofit, path fixo /auth/login)
         → EnvironmentInterceptor reescreve host/scheme/porta com base na seleção atual
         → AuthInterceptor injeta Bearer (se houver token — não há, ainda não logou)
         → resposta processada normalmente pelo apiCall/ApiResult já existente
```

## Tratamento de Erro

Nenhum caminho novo. Rota/ambiente errado (Tailscale sem VPN, LAN fora de casa, URL customizável incorreta) produz `IOException`/timeout, já mapeado pelo `apiCall` existente para `ApiResult.NetworkError` → mesma mensagem "Sem conexão. Tente novamente." já implementada no `LoginViewModel`. Campo de URL customizável faz validação mínima (não-vazio; prefixa `http://` se faltar scheme).

## Testes

- `EnvironmentUrlResolver.resolveBaseUrl()` — função pura, tabela de casos cobrindo os 4 ambientes × 2 rotas + o caso customizável
- `EnvironmentInterceptor` — teste com `MockWebServer` (mesmo padrão do `AuthInterceptorTest` já existente), confirma reescrita de host/scheme por requisição
- `EnvironmentPreferences` — leitura/escrita
- `EnvironmentSelector` (Compose) — sem teste automatizado; validação manual (convenção já usada no resto do app para UI Compose)

## Fora de Escopo (nesta v1)

- Trocar de ambiente estando logado (exigiria botão de logout, inexistente hoje)
- Auto-detecção de rede (tentar LAN, cair para Tailscale automaticamente)
- Ligar/desligar a VPN Tailscale a partir do app
- Validação de certificado customizado (Tailscale já usa Let's Encrypt, confiável por padrão no Android)
- Ambiente `Local` com host fixo para dispositivo físico (permanece só via campo customizável)
