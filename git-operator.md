### Workflow de Branches e PRs
**Regras invioláveis:**
- Este repositório utiliza o **Git Worktree** para gerenciamento de ambiente local. O objetivo é garantir isolamento total entre tarefas, eliminação do uso de `git stash` e proteção das branches principais.
- Nenhuma feature ou hotfix é desenvolvida diretamente nas branches `develop` ou `main`.
- Toda e qualquer nova branch **DEVE** nascer a partir da `develop` atualizada (nunca de `main` ou de outra feature branch)
- **Cada agente trabalha na sua própria branch separada**, sempre derivada de `develop`. Concluído e aprovado o trabalho, solita o merge na `develop`. Agentes em paralelo nunca compartilham branch.
- Ao concluir uma feature com sucesso, indicar em que branch está e sugerir merge na `develop` local
- PRs devem ser o mais cumulativos possível: agrupar issues relacionadas da mesma sessão em uma única PR em vez de abrir uma por issue
- PRs sempre apontam para `main` e partem de `develop` (o fluxo é `feature → develop → PR → main`)
- Nunca fazer merge de `develop` → `main` diretamente; sempre via PR com revisão
- Deletar branches e worktrees locais após o merge em `develop` para manter o repositório limpo

**Fluxo padrão:**
# 1. Vá para a pasta base estável
cd ~/fintech-core

# 2. Garanta que sua base local tem as últimas alterações do servidor
git pull origin develop

# 3. Crie a nova worktree e branch baseada em develop
git worktree add -b nome-da-sua-branch ~/fintech-core/.worktrees/nome-da-sua-branch develop

# 4. Navegue para o novo ambiente isolado
cd ~/fintech-core/.worktrees/nome-da-sua-branch

# 5. Desenvolva o que foi pedido
Trabalhe normalmente dentro da pasta criada. Suas dependências e arquivos temporários não afetarão as outras branches.

# 6. Finalização e Limpeza
Após finalização e aprovação explícita do merge com a develop e push da mesma ao remote, sugira abertura de PR e limpe seu ambiente local para economizar espaço:

git worktree remove ~/fintech-core/.worktrees/nome-da-sua-branch

### Commits
- Mensagens em português, descritivas, no imperativo ("adiciona", "corrige", "implementa")
- Nunca incluir co-autoria (`Co-Authored-By`) nas mensagens de commit

### Templates
- `.github/ISSUE_TEMPLATE/` — templates de issue (bug, feature, chore) + config.yml
- `.github/pull_request_template.md` — template de PR com checklist das regras invioláveis

### Referências
- [Conventional Commits](https://www.conventionalcommits.org/) - Commit format