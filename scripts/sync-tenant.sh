#!/bin/bash

# fintech-core — Sincronização de UM tenant entre local e Railway.
#
# Uso:
#   ./scripts/sync-tenant.sh pull   # Railway -> local  (Railway é a origem)
#   ./scripts/sync-tenant.sh push   # local   -> Railway (local é a origem)
#
# Cada execução é UNIDIRECIONAL: substitui os dados do tenant-alvo no DESTINO
# pelos dados da ORIGEM. Não há merge — o que estiver no destino para esse
# tenant é apagado e recarregado. Os demais tenants do destino não são tocados.
#
# Premissa: o schema (migrations V1–V17) é idêntico nos dois lados.

set -euo pipefail

# --- Carregar .env (mesmo padrão do sync-db.sh, suporta & e ? nas URLs) ---
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

# Tenant-alvo: vem do .env (SYNC_TENANT_ID) e pode ser sobrescrito inline.
TENANT="${SYNC_TENANT_ID:-}"
if [ -z "$TENANT" ]; then
  echo "❌ SYNC_TENANT_ID não definida. Defina o tenant-alvo no .env"
  echo "   (ex.: SYNC_TENANT_ID=70f9a777-b1ad-4912-a2ac-8d6b17de6608)"
  exit 1
fi

export PGCONNECT_TIMEOUT=10

# URLs em formato libpq (psql/pg_dump), NÃO jdbc.
#   Local: porta 5432 exposta pelo docker-compose. Sobrescreva em .env se quiser.
#   Railway: URL PÚBLICA (DATABASE_PUBLIC_URL), em .env como DATABASE_URL_RAILWAY.
LOCAL_URL="${DATABASE_URL_LOCAL:-postgresql://admin:secret@localhost:5432/fintech}"
RAILWAY_URL="${DATABASE_URL_RAILWAY:-}"

# --- Resolver direção ---
DIRECTION="${1:-}"
case "$DIRECTION" in
  pull) SRC="$RAILWAY_URL"; DST="$LOCAL_URL";  SRC_NAME="Railway"; DST_NAME="local"   ;;
  push) SRC="$LOCAL_URL";   DST="$RAILWAY_URL"; SRC_NAME="local";   DST_NAME="Railway" ;;
  *)
    echo "uso: $0 <pull|push>"
    echo "  pull = Railway -> local"
    echo "  push = local   -> Railway"
    exit 1
    ;;
esac

if [ -z "$RAILWAY_URL" ]; then
  echo "❌ DATABASE_URL_RAILWAY não definida. Adicione a URL PÚBLICA do Railway"
  echo "   (Railway → Postgres → Variables → DATABASE_PUBLIC_URL) no seu .env."
  exit 1
fi

# --- Confirmação (proteção contra rodar o sentido errado) ---
echo "⚠️  Vai SOBRESCREVER o tenant $TENANT"
echo "    no destino [$DST_NAME] com os dados da origem [$SRC_NAME]."
echo "    Os dados desse tenant no [$DST_NAME] serão apagados e recarregados."
read -r -p "Confirma? (digite 'yes'): " ans
[ "$ans" = "yes" ] || { echo "Abortado."; exit 1; }

# --- Staging: extrair as linhas do tenant da ORIGEM para arquivos temporários ---
# Ordem de carga (pais -> filhos). credit_card_details não tem tenant_id:
# é filtrado pelos accounts do tenant.
LOAD_ORDER=(tenants users accounts credit_card_details categories \
            installment_groups invoices transactions recurring_budget_items \
            budget_cycles budget_items invitations)

STAGE_DIR=$(mktemp -d)
trap 'rm -rf "$STAGE_DIR"' EXIT

# Monta o SELECT de cada tabela (a maioria filtra por tenant_id; tenants e
# credit_card_details são casos especiais).
select_for() {
  case "$1" in
    tenants)             echo "SELECT * FROM tenants WHERE id='$TENANT'" ;;
    credit_card_details) echo "SELECT cc.* FROM credit_card_details cc JOIN accounts a ON cc.account_id=a.id WHERE a.tenant_id='$TENANT'" ;;
    *)                   echo "SELECT * FROM $1 WHERE tenant_id='$TENANT'" ;;
  esac
}

echo "🔍 Extraindo tenant da origem [$SRC_NAME]..."
for t in "${LOAD_ORDER[@]}"; do
  psql "$SRC" -v ON_ERROR_STOP=1 -q \
    -c "\copy ($(select_for "$t")) TO '$STAGE_DIR/$t.dat'"
done

# --- Carga no DESTINO, tudo numa transação com FKs desligadas ---
# session_replication_role=replica desliga checagem de FK e cascatas durante a
# carga -> ordem entre tabelas e a auto-FK de categories deixam de importar.
echo "🧹 Limpando o tenant no destino [$DST_NAME] e recarregando..."

LOAD_SQL="$STAGE_DIR/_load.sql"
{
  echo "BEGIN;"
  echo "SET session_replication_role = replica;"
  # Apaga o tenant no destino. Sob replica, cascata não dispara -> apaga
  # credit_card_details explicitamente.
  echo "DELETE FROM credit_card_details WHERE account_id IN (SELECT id FROM accounts WHERE tenant_id='$TENANT');"
  for t in budget_items budget_cycles recurring_budget_items transactions \
           invoices installment_groups accounts categories invitations users; do
    echo "DELETE FROM $t WHERE tenant_id='$TENANT';"
  done
  echo "DELETE FROM tenants WHERE id='$TENANT';"
  # Recarrega na ordem pais->filhos (irrelevante sob replica, mas mantém legível).
  for t in "${LOAD_ORDER[@]}"; do
    echo "\\copy $t FROM '$STAGE_DIR/$t.dat'"
  done
  echo "SET session_replication_role = DEFAULT;"
  echo "COMMIT;"
} > "$LOAD_SQL"

psql "$DST" -v ON_ERROR_STOP=1 -q -f "$LOAD_SQL"

echo "✅ Tenant $TENANT sincronizado: $SRC_NAME -> $DST_NAME."
[ "$DST_NAME" = "local" ] && echo "💡 Reinicie o backend local se estiver rodando."
