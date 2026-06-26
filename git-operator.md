### Workflow de Branches e PRs
**Regras invioláveis:**
- Toda branch de feature parte de `develop` (nunca de `main` ou de outra feature branch)
- **Cada agente trabalha na sua própria branch separada**, sempre derivada de `develop`. Concluído e aprovado o trabalho, faz merge na `develop`. Agentes em paralelo nunca compartilham branch.
- Ao concluir uma feature com sucesso, fazer merge imediato na `develop` local e push
- PRs devem ser o mais cumulativos possível: agrupar issues relacionadas da mesma sessão em uma única PR em vez de abrir uma por issue
- PRs sempre apontam para `main` e partem de `develop` (o fluxo é `feature → develop → PR → main`)
- Nunca fazer merge de `develop` → `main` diretamente; sempre via PR com revisão
- Deletar branches locais após o merge em `develop` para manter o repositório limpo

**Fluxo padrão:**
1. `git checkout -b feature/issue-XYZ develop`
2. Implementar, commitar, testar
3. `git checkout develop && git merge feature/issue-XYZ && git push origin develop`
4. Abrir PR: `feature/issue-XYZ → main` (ou acumular issues relacionadas numa PR única)
5. `git branch -d feature/issue-XYZ`

### Commits
- Mensagens em português, descritivas, no imperativo ("adiciona", "corrige", "implementa")
- Nunca incluir co-autoria (`Co-Authored-By`) nas mensagens de commit

### Templates
- `.github/ISSUE_TEMPLATE/` — templates de issue (bug, feature, chore) + config.yml
- `.github/pull_request_template.md` — template de PR com checklist das regras invioláveis

### Referências
- [Conventional Commits](https://www.conventionalcommits.org/) - Commit format