#!/bin/bash

# fintech-core DB Sync Script (Neon -> Local)
# Este script clona o banco de dados da Neon.tech para o ambiente Docker local.

# 1. Carregar variáveis de ambiente de forma segura (suporta caracteres especiais como & e ?)
load_env() {
  local env_file=$1
  if [ -f "$env_file" ]; then
    # Lê linha por linha, ignorando comentários e linhas vazias, e exporta
    while IFS= read -r line || [ -n "$line" ]; do
      [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue
      export "$line"
    done < "$env_file"
  fi
}

load_env ".env"
load_env ".env.local"

NEON_URL=${DATABASE_URL_NEON}
# Timeout de conexão em segundos
export PGCONNECT_TIMEOUT=10

if [ -z "$NEON_URL" ]; then
  echo "❌ Erro: DATABASE_URL_NEON não encontrada."
  echo "Crie um arquivo .env ou .env.local na raiz baseado no scripts/.env.template e preencha a URL da Neon."
  exit 1
fi

# Configurações do banco local (baseado no docker-compose.yml)
LOCAL_CONTAINER="fintech-postgres"
LOCAL_USER="admin"
LOCAL_DB="fintech"

echo "⏳ Iniciando sincronização: Neon.tech ➔ Local Docker..."

# 2. Verificar se o container local está rodando
if ! docker ps | grep -q "$LOCAL_CONTAINER"; then
  echo "❌ Erro: O container $LOCAL_CONTAINER não está rodando."
  echo "Execute 'docker-compose up -d' primeiro."
  exit 1
fi

# 3. Validar conexão com a Neon e realizar o Dump
echo "🔍 Validando conexão e baixando dados da Neon para arquivo temporário..."
TEMP_DUMP=$(mktemp)

# Tenta realizar o dump. Se falhar, o banco local nem é tocado.
if ! pg_dump --no-owner --no-privileges --clean --if-exists "$NEON_URL" -f "$TEMP_DUMP"; then
  echo "❌ Erro: Não foi possível realizar o dump da Neon. Verifique sua conexão e a URL no .env."
  rm -f "$TEMP_DUMP"
  exit 1
fi

# 4. Limpar o banco local e Restaurar
echo "🧹 Dump concluído. Limpando banco local ($LOCAL_DB) e restaurando..."

# Reset do schema public
if ! docker exec -t "$LOCAL_CONTAINER" psql -U "$LOCAL_USER" -d "$LOCAL_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" > /dev/null; then
  echo "❌ Erro ao limpar o banco local."
  rm -f "$TEMP_DUMP"
  exit 1
fi

# Restauração a partir do arquivo temporário
if cat "$TEMP_DUMP" | docker exec -i "$LOCAL_CONTAINER" psql -U "$LOCAL_USER" -d "$LOCAL_DB" > /dev/null; then
  echo "✅ Sincronização concluída com sucesso!"
  echo "💡 Dica: Se o backend estava rodando, ele pode precisar ser reiniciado para refletir as mudanças."
else
  echo "❌ Ocorreu um erro durante a restauração local."
  rm -f "$TEMP_DUMP"
  exit 1
fi

# Limpeza do arquivo temporário
rm -f "$TEMP_DUMP"
