
## 3. `docs/adr/ADR-003-infra-flyio.md`

```markdown
# ADR-003: Infraestrutura — Fly.io (PaaS Gerenciado)

## Status
Aceito

## Contexto
Requisitos:
- Custo operacional mínimo (solo dev, bootstrapped)
- Postgres gerenciado (não quero gerenciar backup/vacuum/replica)
- Deploy simples (`fly deploy`)
- Scale-to-zero para ambientes de dev/staging
- Baixa latência BR (região `gru` / `gig`)

Opções avaliadas:
| Opção | Custo/mês (inicial) | Postgres | Deploy | Manutenção |
|-------|---------------------|----------|--------|------------|
| **Fly.io Hobby** | ~$5-10 (R$ 25-50) | Incluído (1GB) | `fly deploy` | Zero |
| Railway Pro | ~$20-30 | Separado (~$5) | Git push | Baixa |
| Render | ~$15-25 | Incluído | Git push | Baixa |
| AWS/GCP (ECS/RDS) | ~$50-100+ | RDS gerenciado | Complexo | Alta |
| VPS (DigitalOcean/Hetzner) | ~$6-12 | Self-managed | Docker Compose | Média |

**Decisão do usuário**: Fly.io — melhor custo/benefício para solo dev com Postgres gerenciado incluso.

## Decisão
Deploy em **Fly.io** com arquitetura:

┌─────────────────────────────────────────────────────────────┐
│                      Fly.io (região gru)                    │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │ App Machine  │    │ App Machine  │    │  (scale-to-0) │  │
│  │ shared-cpu-1x│    │ shared-cpu-1x│    │  staging     │  │
│  │ 256MB RAM    │    │ 256MB RAM    │    │              │  │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘  │
│         │                   │                   │           │
│         └───────────────────┼───────────────────┘           │
│                             ▼                                │
│                    ┌────────────────┐                        │
│                    │  Postgres 1GB  │  (HA opcional depois) │
│                    │  + PgBouncer   │                        │
│                    └────────┬───────┘                        │
│                             │                                │
│                    ┌────────┴────────┐                       │
│                    │   Redis 256MB   │  (sessões, cache)    │
│                    └─────────────────┘                       │
└─────────────────────────────────────────────────────────────┘

### Configuração (`fly.toml`)
```toml
app = "fintech-core"
primary_region = "gru"

[build]
  dockerfile = "Dockerfile"

[env]
  SPRING_PROFILES_ACTIVE = "prod"
  JAVA_OPTS = "-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = true      # scale-to-zero
  auto_start_machines = true
  min_machines_running = 0       # 1 em prod após 10 tenants
  processes = ["app"]

[[vm]]
  memory = "256mb"
  cpu_kind = "shared"
  cpus = 1

[metrics]
  port = 9090
  path = "/actuator/prometheus"
Secrets (via fly secrets set)
fly secrets set \
  JWT_SECRET="..." \
  STRIPE_SECRET_KEY="sk_live_..." \
  STRIPE_WEBHOOK_SECRET="whsec_..." \
  SPRING_DATASOURCE_URL="postgres://..." \
  SPRING_DATASOURCE_USERNAME="..." \
  SPRING_DATASOURCE_PASSWORD="..."
CI/CD (GitHub Actions)
- main branch → `fly deploy