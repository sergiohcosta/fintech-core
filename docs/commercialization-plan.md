# Plano de Comercialização — Fintech SaaS Multi-Tenant (MVP 30 Dias)

> Última atualização: 2026-06-19 | Status: **Aprovado para execução**

---

## 1. Estratégia de Preço

### Fase Piloto (primeiros 5-10 usuários)
- **Modelo**: "Pay what you want" — mínimo R$ 1/mês
- **Objetivo**: Validar disposição a pagar + coletar feedback qualitativo
- **Duração**: Até atingir 10 usuários pagos ou 60 dias

### Fase Pós-MVP (Freemium Preparado)
| Plano | Limites | Preço | Features |
|-------|---------|-------|----------|
| **Free** | 1 conta CHECKING, 1 CASH, 1 INVESTMENT, 1 CREDIT_CARD · Transações ilimitadas | R$ 0 | Dashboard, categorias, faturas, parcelamento, budget cycles |
| **Pro** | Contas ilimitadas · API access · White-label básico · Prioridade suporte | R$ 29-39/mês | Tudo do Free + contas ilimitadas + webhooks + export avançado |

> **Implementação**: `Tenant.plan` enum `{FREE, PRO, PILOT}` + `Tenant.planExpiresAt` + feature flags por plano (inicialmente via `@ConditionalOnProperty`, depois FF4J).

---

## 2. Cronograma 30 Dias Úteis (6 Semanas)

### Semana 1-2: Fundação & Infra (Dias 1-10)
| Dia | Task | Critério de Pronto |
|-----|------|-------------------|
| 1-2 | **Fly.io setup**: `fly launch`, `fly.toml`, Postgres 1GB, Redis 256MB, `fly deploy` funcionando | App respondendo em `https://fintech.fly.dev` |
| 3-4 | **GitHub Actions CI/CD**: `mvn test` + `npm test` + `docker build` + `fly deploy` staging | Pipeline verde em PR + auto-deploy main |
| 5-6 | **Observabilidade**: Actuator + Prometheus endpoint + Grafana Cloud (free tier) dashboards | Métricas JVM, HTTP, DB pool, business (tenants ativos) visíveis |
| 7-8 | **Stripe Sandbox**: Conta, Products/Prices (PILOT + PRO), Webhook local `stripe-cli` | `checkout.session.completed` cria tenant + assinatura |
| 9-10 | **Webhook Produção**: Endpoint idempotente + retries exponenciais + logs estruturados | Webhook processa 100% eventos em staging |

### Semana 3-4: Billing Completo (Dias 11-20)
| Dia | Task |
|-----|------|
| 11-12 | **Customer Portal** integrado no perfil (self-service cancel/upgrade/invoices) |
| 13-14 | **Trial Logic**: 14 dias → bloqueia features Pro se não convertido (middleware + guard frontend) |
| 15-16 | **Tenant.plan** migration + enum + seed PILOT para usuários existentes |
| 17-18 | **Feature Gates**: `@ConditionalOnProperty("app.plan.pro.enabled")` em controllers Pro |
| 19-20 | **Testes E2E Billing**: Checkout → Trial → Conversão → Portal → Cancelamento |

### Semana 5: Onboarding PF + OFX (Dias 21-25)
| Dia | Task |
|-----|------|
| 21-22 | **Wizard Onboarding** (`/onboarding/*`): Step 1 Plano → Step 2 Dados → Step 3 Importação → Step 4 Dashboard |
| 23-24 | **Parser OFX** (`ofx4j`): Upload → Preview categorizado (taxonomyCode) → Confirma → Persiste |
| 25 | **Categorias Default por Segmento**: Seed `taxonomyCode` (HOUSING, FOOD, TRANSPORT, etc.) |

### Semana 6: Polimento + Piloto (Dias 26-30)
| Dia | Task |
|-----|------|
| 26 | **LGPD Mínimo**: `GET /api/me/export` (stream JSON) + `DELETE /api/me` (anonymize + soft delete) |
| 27 | **Landing Page** (Astro/Next.js static em `/public` ou repo separado): Hero, features, pricing, CTA Stripe Checkout |
| 28 | **Docs Mínimas**: `docs.fintech.app` (Docusaurus) — Guia 5 min + API Reference (Redoc) + Changelog |
| 29 | **Onboarding Assistido**: Acompanhar 1º piloto manualmente, corrigir fricções |
| 30 | **Retrospectiva + Ajustes** → Documentar lições → Planejar Fase 2 |

---

## 3. ADRs a Criar (3 arquivos)

| ADR | Título | Decisão |
|-----|--------|---------|
| **ADR-002** | Billing: Stripe Subscription + Customer Portal | Stripe only; webhook idempotente; trial 14d; portal self-service |
| **ADR-003** | Infra: Fly.io (PaaS Gerenciado) | App + Postgres + Redis no Fly; scale-to-zero; $5-10/mês inicial |
| **ADR-004** | Multi-tenancy: Row Level Security (RLS) Nativo Postgres | `tenant_id` em todas as tabelas + policy `USING (tenant_id = current_setting('app.current_tenant')::uuid)` — prepara sharding futuro |

---

## 4. Issues GitHub a Abrir (Fase 0 - Semana 1-2)

| Issue | Template | Labels |
|-------|----------|--------|
| `#chore: Fly.io setup + deploy inicial` | chore | infra, phase-0 |
| `#chore: GitHub Actions CI/CD pipeline` | chore | ci, phase-0 |
| `#feat: Actuator + Prometheus + Grafana Cloud` | feature | observability, phase-0 |
| `#feat: Stripe Sandbox + webhook local` | feature | billing, phase-0 |
| `#feat: Stripe Webhook produção idempotente` | feature | billing, phase-0 |

---

## 5. Riscos & Mitigações (Top 3)

| Risco | Mitigação |
|-------|-----------|
| **Parser OFX falha em bancos brasileiros** | Spike Day 21: testar OFX real de 3 bancos (Bradesco, Nubank, Inter) — fallback CSV manual |
| **Stripe webhook falha silenciosamente** | Log estruturado + alerta Grafana (webhook failures > 0 em 5min) + dead letter table no DB |
| **Fly.io cold start lentidão** | `min_machines_running = 1` no `fly.toml` (custa ~$3/mês extra) — desliga após 10 tenants |

---

## 6. Definição de Pronto (MVP)

- [ ] Deploy Fly.io estável 7 dias sem restart manual
- [ ] Checkout Stripe → Trial 14d → Conversão automática → Portal funciona
- [ ] Onboarding: Usuário cria conta → Importa OFX → Vê dashboard populado em < 10 min
- [ ] 1 piloto pagando (mesmo que R$ 1) + feedback qualitativo documentado
- [ ] Zero secrets no repo; `fly secrets` em produção

---

## 7. Próximos Passos Imediatos (Pós-Aprovação)

1. **Criar** `docs/commercialization-plan.md` (este conteúdo)
2. **Criar** 3 ADRs em `docs/adr/`
3. **Abrir** 5 issues GitHub (Semana 1-2)
4. **Iniciar** implementação: **Fly.io setup + CI/CD** (Dia 1-2)