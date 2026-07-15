# ADR-002: Billing — Stripe Subscription + Customer Portal

## Status
Aceito

## Contexto
O MVP precisa cobrar usuários em 30 dias. Opções avaliadas:
- **Stripe**: Global, docs excelentes, Customer Portal nativo, webhooks robustos, trial nativo, portal self-service
- **Pagar.me/Asaas**: Boleto/PIX nativo, mas complexidade BR (split, antecipação, homolgação), 2x tempo de integração
- **Próprio + Mercado Pago**: Controle total, mas reinventar roda (dunning, invoices, portal, compliance PCI)

**Decisão do usuário**: Stripe apenas no MVP; Pagar.me apenas se houver demanda real de boleto/PIX no Brasil (Fase 2).

## Decisão
Usar **Stripe Billing** como único provedor de pagamentos no MVP.

### Arquitetura
┌─────────────┐     checkout.session.completed     ┌──────────────┐
│  Frontend   │ ─────────────────────────────────> │  Stripe      │
│  (Checkout) │                                    │  (SaaS)      │
└─────────────┘                                    └──────┬───────┘
                                                          │
                                                invoice.payment_succeeded
                                                          │
                                                          ▼
                                                 ┌────────────────┐
                                                 │  Webhook       │
                                                 │  /api/webhooks │
                                                 │  /stripe       │
                                                 └───────┬────────┘
                                                         │
                                    ┌────────────────────┼────────────────────┐
                                    ▼                    ▼                    ▼
                            ┌───────────────┐    ┌───────────────┐    ┌───────────────┐
                            │ Tenant.plan   │    │ Subscription  │    │ Audit Log     │
                            │ = PILOT/PRO   │    │ status sync   │    │ (imutável)    │
                            └───────────────┘    └───────────────┘    └───────────────┘

### Componentes
1. **StripeWebhookController** — endpoint único `/api/webhooks/stripe` com verificação de assinatura (`Stripe-Signature`)
2. **WebhookProcessor** — idempotência via `StripeEvent.stripeEventId` (UNIQUE); processa:
   - `checkout.session.completed` → cria/atualiza `Tenant.subscription`
   - `invoice.payment_succeeded` → renova `planExpiresAt`
   - `customer.subscription.deleted` → `Tenant.plan = FREE`
3. **SubscriptionService** — encapsula lógica de plano, trial, feature gates
4. **Customer Portal** — botão no perfil chama `Stripe.billingPortal.sessions.create()` → redirect

### Feature Gates (Fase 1: nativo Spring)
```java
@ConditionalOnProperty(name = "app.plan.pro.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/pro")
public class ProFeaturesController { ... }
Trial Logic
- Novo tenant → plan = PILOT, planExpiresAt = now() + 14 days
- Middleware PlanEnforcementFilter intercepta requests → se planExpiresAt < now() && plan != PRO → 403 PLAN_EXPIRED
- Frontend: AuthGuard + PlanGuard redireciona para /pricing se expirado
Consequências
Positivas
- Setup em < 2 horas (Stripe CLI + dashboard)
- Zero manutenção de invoices, dunning, retry, portal
- Pronto para expansão global (USD, EUR, etc.)
- Customer Portal = zero código para "cancelar assinatura"
Negativas
- Sem boleto/PIX no MVP (pode barrar alguns usuários BR)
- Taxa Stripe (~3.9% + R$ 0,60) > Pagar.me em volume alto
- Vendor lock-in (mitigado: webhook idempotente + dados no nosso DB)
Riscos
Risco	Mitigação
Webhook falha → tenant não ativado	Idempotência + dead letter table + alerta Grafana
Stripe muda API	Versionamento Stripe-Version header fixo; testes contract
Fraude chargeback	Stripe Radar (grátis) + validação manual piloto