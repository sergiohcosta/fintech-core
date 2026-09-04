# Recuperação de senha — Design

> Status: aprovado (dev, sessão 2026-09-04).
> Data: 2026-09-04

## Problem Statement

Não existe caminho de recuperação de senha hoje — usuário que esquece a senha fica travado,
sem fallback (só ADMIN pode reenviar convite, e convite é pra criar conta nova, não resetar
uma existente). Precisa de fluxo público, seguro (sem enumeração de usuário, sem token
reaproveitável) e que invalide sessões antigas quando a senha muda (requisito do dev — trocar
senha sem matar sessões já abertas deixa uma janela de uso indevido se a troca foi motivada por
suspeita de comprometimento).

## Fluxo

```
POST /auth/forgot-password {email}  → sempre 200 (nunca revela se o email existe)
  → se existe usuário ativo: gera token, salva, envia email com link
POST /auth/reset-password {token, newPassword} → 200 com token inválido/expirado → 400/410
  → troca hash, marca token usado, invalida sessões emitidas antes da troca
```

Mesma postura anti-enumeração do `/auth/login` (resposta genérica) e mesmo padrão de token de
uso único já provado em `Invitation` (V6): `token UUID` opaco, `expiresAt`, `used`.

## Decisões de design

### 1. Envio de email: SMTP (Spring Mail) + relay Resend

Decisão do dev (não IA): `spring-boot-starter-mail` + `JavaMailSender`, credenciais via env var
(`spring.mail.*`). Sem infra de email hoje no projeto — este é o primeiro uso. Código
agnóstico de provider (só host/porta/usuário/senha); provider escolhido pra dev/hmg/prod é o
**Resend** (relay SMTP, `smtp.resend.com:587`) — testado inicialmente com Gmail, trocado por
melhor deliverability sem depender de reputação de IP própria (homelab residencial não tem
DNS/PTR pra SPF/DKIM confiável). Sem domínio verificado no Resend, o envio só funciona pro
email da própria conta (modo de teste do provider) — verificar domínio é prova pendente antes
de considerar a feature pronta pra usuários reais.

**Achado de segurança operacional (não previsto no design original):** `MailSenderAutoConfiguration`
não é condicional como o `GeminiVisionClient` — declarar `MAIL_HOST` etc **sem default** em
`application-prod.properties` quebraria o boot inteiro de hmg/prod (ambos rodam perfil `prod`)
sempre que a env var não estivesse setada, mesmo que ninguém chamasse `/auth/forgot-password`.
Corrigido: mail herda os defaults de `application.properties` (`localhost`) em todo perfil;
`PasswordResetService.requestReset` captura `MailException` e não propaga — sem `MAIL_*` real
configurado, a feature degrada (token gerado, email não sai) em vez de derrubar o backend
inteiro. Manifests em `homelab-k8s/projects/fintech-core` (`MAIL_HOST/PORT/USERNAME` no
ConfigMap, `MAIL_PASSWORD` no Secret, todos `optional: true`).

### 2. Nova entidade `PasswordResetToken`, não reaproveitar `Invitation`

Semânticas diferentes (convite cria conta nova; reset troca senha de conta existente) — misturar
no mesmo modelo criaria condicionais divergentes na mesma tabela. Espelha `Invitation` 1:1
(mesmos campos: `token UNIQUE`, `expiresAt`, `used`), mas vinculada a `User` (não a `Tenant`) —
lookup é sempre por token único global, nunca por listagem escopada a tenant. Por isso **sem
`tenant_id` denormalizado** — não é um padrão de query por tenant, é sempre "acha este token
específico"; isolamento vem da FK `user_id`. Consequência: fica **fora do rollout de RLS**
(`fintech-core-research-frontier` / ADR-006) — não é omissão, é porque o padrão de acesso da
tabela nunca precisa da policy.

Token **sem hash no banco** (mesmo padrão de `Invitation.token`) — consistência com o único
precedente do repo; introduzir hash aqui e não lá criaria dois padrões de segurança divergentes
sem justificativa (nenhum incidente motivando a diferença).

Expiração: **1 hora** (vs. 7 dias do convite) — reset de senha é ação mais sensível que aceitar
convite, janela de exposição menor por design.

### 3. Matar sessões ativas ao trocar senha — sem storage novo

JWT é stateless hoje (`TokenService`, sem blacklist/Redis). Introduzir revogação completa
(blacklist com TTL) seria a solução "correta" de livro-texto, mas é infraestrutura nova pra um
projeto de instância única — desproporcional ao problema real.

**Solução adotada:** coluna `users.password_changed_at` (nullable). `TokenService.generateToken`
grava `issuedAt` explícito no JWT (`withIssuedAt`, já suportado pela lib `auth0:java-jwt:4.4.0`
em uso). `SecurityFilter`, depois de resolver o `User` do token, compara: se
`issuedAt < user.passwordChangedAt`, trata como token inválido (mesmo caminho de rejeição que
token expirado/malformado hoje — não autentica, loga WARN).

Efeito: todo JWT emitido antes do reset para de autenticar na **próxima** requisição de cada
sessão aberta — não é revogação instantânea client-side (não existe canal pra isso, é API REST
sem WebSocket), é lazy, mesmo modelo já usado pela expiração natural do token (2h). Aceitável:
o objetivo é fechar a janela de uso da sessão antiga, não notificar o outro dispositivo em
tempo real.

**Blast radius controlado:** `SecurityFilter` ganha uma chamada nova (`tokenService.getIssuedAt`)
só executada **depois** de já ter resolvido `userDetails`. Os 12 `*ControllerTest` existentes
mockam apenas `tokenService.validateToken(...)`; sem stub do método novo, Mockito retorna `null`
→ o check de revogação é pulado → comportamento idêntico ao atual. Nenhum teste existente
precisa ser tocado por causa disso.

## Contrato (`api-spec/openapi.yaml`)

Dois paths novos, sem `security` (rota pública), no mesmo grupo de `/auth/login|register|accept-invite`:

- `POST /auth/forgot-password` — body `{email}`, resposta `200` vazia (sempre, mesmo email
  inexistente).
- `POST /auth/reset-password` — body `{token, newPassword}`, resposta `200` vazia; `400` corpo
  vazio se token inválido/expirado/usado (não distingue qual — mesma postura anti-enumeração,
  não vazar "token existe mas expirou" vs "token não existe").

Impacto SemVer: **MINOR** (endpoints novos, aditivos, contrato existente intacto).

## Fora de escopo

- Revogação client-side em tempo real (WebSocket/push) — API REST não tem canal pra isso.
- RLS em `password_reset_tokens` — padrão de acesso não é por tenant (ver decisão 2).
- Notificar usuário por email quando a senha muda (email de "sua senha foi alterada") — feature
  própria, não bloqueia o fluxo core.
- Reenvio de token (rate limit já cobre abuso; se expirar, usuário pede de novo via
  forgot-password).

## Critério de conclusão

- [ ] `POST /auth/forgot-password` e `POST /auth/reset-password` funcionais, sem enumeração.
- [ ] Token de uso único, expira em 1h.
- [ ] Sessões (JWTs) emitidas antes do reset param de autenticar após a troca.
- [ ] Rate limit em `forgot-password` (chave = email, mesma régua conceitual do login).
- [ ] Frontend: telas `forgot-password` e `reset-password`, link no `login`.
- [ ] Contrato, dataset (`docs/http/seed-dataset.http`) e `database-schema.md` atualizados.
