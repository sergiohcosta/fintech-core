#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

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
kill_port 8080
kill_port 4200

sleep 1

echo ""
echo "==> Iniciando backend (porta 8080)..."
cd "$ROOT/backend"
./mvnw spring-boot:run -q &
BACKEND_PID=$!

echo "==> Iniciando frontend (porta 4200)..."
cd "$ROOT/frontend"
npm start &
FRONTEND_PID=$!

echo ""
echo "Backend PID : $BACKEND_PID"
echo "Frontend PID: $FRONTEND_PID"
echo ""
echo "Pressione Ctrl+C para encerrar ambos."

trap 'echo ""; echo "Encerrando..."; kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; exit 0' INT TERM

wait
