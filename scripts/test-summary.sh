#!/bin/bash

# fintech-core — Roda a suíte de testes e imprime só o resumo agregado.
# Evita grep-fishing no stdout do Maven/Vitest e timeouts em sessões de agente
# (a suíte completa do backend passa de 7 minutos).
#
# Uso:
#   ./scripts/test-summary.sh backend    # mvnw test + resumo do surefire
#   ./scripts/test-summary.sh frontend   # ng test --watch=false + resumo
#   ./scripts/test-summary.sh            # ambos

set -uo pipefail
cd "$(dirname "$0")/.."

run_backend() {
  echo "▶ Backend (./mvnw test — pode levar >7 min)"
  (cd backend && ./mvnw -B test) > /tmp/fintech-mvn-test.log 2>&1
  local rc=$?
  # Agrega os surefire reports em uma linha por classe com falha + total
  python3 - <<'EOF'
import glob, re
tot = f = e = s = 0
fails = []
for p in glob.glob("backend/target/surefire-reports/*.txt"):
    head = open(p, encoding="utf-8", errors="replace").read()
    m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", head)
    if not m: continue
    t, fa, er, sk = map(int, m.groups())
    tot += t; f += fa; e += er; s += sk
    if fa or er:
        fails.append(f"  FAIL {p.split('/')[-1]}: {fa} failures, {er} errors")
print(f"Backend: {tot} tests, {f} failures, {e} errors, {s} skipped")
print("\n".join(fails))
EOF
  [ $rc -ne 0 ] && echo "  (build exit=$rc — log completo em /tmp/fintech-mvn-test.log)"
  return $rc
}

run_frontend() {
  echo "▶ Frontend (ng test --watch=false)"
  (cd frontend && npx ng test --watch=false) > /tmp/fintech-ng-test.log 2>&1
  local rc=$?
  grep -E "Test Files|Tests  |FAIL" /tmp/fintech-ng-test.log | tail -20
  [ $rc -ne 0 ] && echo "  (exit=$rc — log completo em /tmp/fintech-ng-test.log)"
  return $rc
}

case "${1:-all}" in
  backend)  run_backend ;;
  frontend) run_frontend ;;
  all)      run_backend; b=$?; run_frontend; f=$?; exit $((b || f)) ;;
  *) echo "Uso: $0 [backend|frontend|all]"; exit 1 ;;
esac
