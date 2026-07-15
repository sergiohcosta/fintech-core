#!/bin/bash

# fintech-core — Lista e remove worktrees órfãs (feature já mesclada em develop
# ou worktrees agent-* abandonadas). Sempre pede confirmação antes de remover.
#
# Uso: ./scripts/clean-worktrees.sh

set -euo pipefail
cd "$(dirname "$0")/.."   # raiz estável — nunca remover worktree com cwd dentro dela

git worktree prune

main_wt=$(git rev-parse --show-toplevel)
git worktree list --porcelain | awk '/^worktree /{print $2}' | while read -r wt; do
  [ "$wt" = "$main_wt" ] && continue
  branch=$(git -C "$wt" branch --show-current 2>/dev/null || echo "?")
  merged=""
  if [ "$branch" != "?" ] && git merge-base --is-ancestor "$branch" develop 2>/dev/null; then
    merged=" [mesclada em develop]"
  fi
  dirty=$(git -C "$wt" status --porcelain 2>/dev/null | head -1)
  echo ""
  echo "Worktree: $wt (branch: $branch)$merged"
  [ -n "$dirty" ] && { echo "  ⚠ tem alterações não commitadas — pulando"; git -C "$wt" status --short | head -10; continue; }
  read -rp "  Remover worktree e branch? [y/N] " ans </dev/tty
  if [ "$ans" = "y" ]; then
    git worktree remove "$wt"
    [ "$branch" != "?" ] && git branch -d "$branch" 2>/dev/null || true
    echo "  ✅ removida"
  fi
done

echo ""
echo "Worktrees restantes:"
git worktree list
