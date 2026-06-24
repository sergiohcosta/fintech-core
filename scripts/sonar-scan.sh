#!/bin/bash

# fintech-core — Análise local no SonarQube (Community, instância local).
#
# Uso:
#   ./scripts/sonar-scan.sh            # back + front
#   ./scripts/sonar-scan.sh backend    # só Java
#   ./scripts/sonar-scan.sh frontend   # só TypeScript
#
# Pré-requisitos:
#   - Instância do SonarQube de pé (default http://localhost:9000)
#   - SONAR_TOKEN no .env (token de análise gerado na UI do Sonar)
#   - Docker de pé para o backend (mvn verify roda a suíte com Testcontainers)

set -euo pipefail

cd "$(dirname "$0")/.."   # raiz do repo

# --- Carregar .env (mesmo padrão do sync-tenant.sh) ---
load_env() {
  local env_file=$1
  if [ -f "$env_file" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
      [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue
      export "$line"
    done < "$env_file"
  fi
}
load_env ".env"
load_env ".env.local"

export SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "❌ SONAR_TOKEN não definida. Gere um token na UI do Sonar"
  echo "   (My Account → Security → Generate Token) e adicione ao .env:"
  echo "   SONAR_TOKEN=seu_token"
  exit 1
fi

target="${1:-all}"

scan_backend() {
  echo "▶ Backend → $SONAR_HOST_URL (projeto fintech-core-backend)"
  ( cd backend && ./mvnw -B verify sonar:sonar \
      -Dsonar.host.url="$SONAR_HOST_URL" \
      -Dsonar.token="$SONAR_TOKEN" )
}

scan_frontend() {
  echo "▶ Frontend → $SONAR_HOST_URL (projeto fintech-core-frontend)"
  # sonar-scanner lê SONAR_HOST_URL e SONAR_TOKEN do ambiente (já exportados acima)
  ( cd frontend && npm run test:cov && npm run sonar )
}

case "$target" in
  backend)  scan_backend ;;
  frontend) scan_frontend ;;
  all)      scan_backend; scan_frontend ;;
  *) echo "Uso: $0 [backend|frontend|all]"; exit 1 ;;
esac

echo "✅ Análise concluída. Resultados em $SONAR_HOST_URL"
