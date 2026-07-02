#!/bin/bash

# fintech-core — Pipeline completo de sincronização do contrato OpenAPI.
# Roda após qualquer edição em api-spec/openapi.yaml:
#   1. Copia o spec para o static do backend (servido em /openapi.yaml)
#   2. Regenera as interfaces Spring (target/, não commitado)
#   3. Regenera o client Angular via Orval
#   4. Remove o auth/auth.service.ts regenerado (gotcha conhecido do Orval)
#
# Uso: ./scripts/api-sync.sh

set -euo pipefail
cd "$(dirname "$0")/.."   # raiz do repo

echo "▶ 1/4 Copiando spec para o backend/static"
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml

echo "▶ 2/4 Regenerando interfaces Spring"
(cd backend && ./mvnw -q generate-sources)

echo "▶ 3/4 Regenerando client Angular (Orval)"
(cd frontend && npm run api:generate)

echo "▶ 4/4 Removendo auth.service.ts regenerado"
rm -f frontend/src/app/core/api/auth/auth.service.ts

echo "✅ Contrato sincronizado (spec → backend → frontend)"
