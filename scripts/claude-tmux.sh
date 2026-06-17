#!/bin/bash
# Sessão tmux para desenvolvimento assistido por Claude Code.
#
# Layout:
#   Pane esquerdo (70%)  — Claude Code (orquestrador + subagentes via API)
#   Pane superior dir.   — git log ao vivo (mostra commits dos subagentes chegando)
#   Pane inferior dir.   — TypeScript watch (erros de tipo em tempo real)
#
# Uso:
#   ./scripts/claude-tmux.sh            # inicia nova sessão
#   ./scripts/claude-tmux.sh attach     # re-anexa sessão existente

set -euo pipefail

SESSION="claude-dev"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Re-anexa se a sessão já existe
if tmux has-session -t "$SESSION" 2>/dev/null; then
  echo "Sessão '$SESSION' já existe — re-anexando."
  tmux attach-session -t "$SESSION"
  exit 0
fi

# Cria sessão com pane principal
tmux new-session -d -s "$SESSION" -n "claude" -x 220 -y 50

# Divide: pane direito (30% da largura)
tmux split-window -h -t "$SESSION:1" -p 30

# Divide pane direito horizontalmente ao meio
tmux split-window -v -t "$SESSION:1.2"

# Pane 1 (esquerda): Claude Code
tmux select-pane -t "$SESSION:1.1"
tmux send-keys -t "$SESSION:1.1" "cd '$ROOT' && claude" C-m

# Pane 2 (dir. superior): git log ao vivo — mostra commits dos subagentes
tmux send-keys -t "$SESSION:1.2" "cd '$ROOT' && watch -n2 'git log --oneline -15 --color=always'" C-m

# Pane 3 (dir. inferior): TypeScript watch — erros em tempo real
tmux send-keys -t "$SESSION:1.3" "cd '$ROOT/frontend' && npx tsc --noEmit --watch 2>&1" C-m

# Foca no pane do Claude
tmux select-pane -t "$SESSION:1.1"

tmux attach-session -t "$SESSION"
