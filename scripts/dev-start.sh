#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

MODE="${1:-both}"

if [[ "$MODE" != "front" && "$MODE" != "back" && "$MODE" != "both" ]]; then
  echo "Uso: $0 [front|back|both]"
  echo "  front — inicia apenas o frontend (porta 4200)"
  echo "  back  — inicia apenas o backend (porta 8080)"
  echo "  both  — inicia ambos (padrão)"
  exit 1
fi

kill_port() {
  local port="$1"
  local pids
  pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "Encerrando processo(s) na porta $port: $pids"
    kill -9 $pids
  else
    echo "Porta $port livre."
  fi
}

echo "==> Liberando portas..."
[[ "$MODE" == "back"  || "$MODE" == "both" ]] && kill_port 8080
[[ "$MODE" == "front" || "$MODE" == "both" ]] && kill_port 4200

sleep 1
echo ""

PIDS=()

if [[ "$MODE" == "back" || "$MODE" == "both" ]]; then
  echo "==> Iniciando backend (porta 8080)..."
  cd "$ROOT/backend"
  ./mvnw spring-boot:run -q &
  BACKEND_PID=$!
  PIDS+=($BACKEND_PID)
  echo "Backend PID : $BACKEND_PID"
fi

if [[ "$MODE" == "front" || "$MODE" == "both" ]]; then
  echo "==> Iniciando frontend (porta 4200)..."
  cd "$ROOT/frontend"
  npm start &
  FRONTEND_PID=$!
  PIDS+=($FRONTEND_PID)
  echo "Frontend PID: $FRONTEND_PID"
fi

echo ""
echo "Pressione Ctrl+C para encerrar."

trap 'echo ""; echo "Encerrando..."; kill "${PIDS[@]}" 2>/dev/null; exit 0' INT TERM

wait
