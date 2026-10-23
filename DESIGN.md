---
name: Nosso Dinheirinho
description: Gestão financeira colaborativa com rigor contábil, previsibilidade de faturas e tranquilidade doméstica
colors:
  primary: "#00838f"
  primary-deep: "#006064"
  primary-container: "#e0f7fa"
  on-primary: "#ffffff"
  tertiary: "#ef6c00"
  tertiary-container: "#ffe0b2"
  on-tertiary: "#ffffff"
  income: "#2e7d32"
  income-bg: "#e8f5e9"
  expense: "#c62828"
  expense-bg: "#ffebee"
  transfer: "#1565c0"
  transfer-bg: "#e3f2fd"
  pending: "#f57f17"
  pending-bg: "#fff8e1"
  surface: "#ffffff"
  surface-variant: "#f5f5f5"
  surface-container-low: "#fafafa"
  on-surface: "#1c1b1f"
  on-surface-variant: "#595959"
  outline: "#79747e"
  outline-variant: "#e0e0e0"
typography:
  display:
    fontFamily: "Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "1.75rem"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "1.35rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "normal"
  title:
    fontFamily: "Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "normal"
  body:
    fontFamily: "Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  label:
    fontFamily: "Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "0.06em"
rounded:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  pill: "28px"
  sheet: "24px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  2xl: "48px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    rounded: "{rounded.pill}"
    padding: "10px 24px"
  button-primary-hover:
    backgroundColor: "{colors.primary-deep}"
  card-default:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.lg}"
    padding: "16px"
  chip-status:
    backgroundColor: "{colors.surface-variant}"
    textColor: "{colors.on-surface-variant}"
    rounded: "{rounded.md}"
    padding: "3px 10px"
---

# Design System: Nosso Dinheirinho

## Overview

**Creative North Star: "O Porto Seguro Familiar"**

O design visual do Nosso Dinheirinho é concebido para transformar a conferência financeira de um momento tenso e burocrático em uma experiência serena, acolhedora e de controle compartilhado. O sistema afasta conscientemente tanto a frieza monocromática dos ERPs legados quanto o excesso de estímulos da cultura cripto/fintech hiper-gamificada. Cada tela privilegia a clareza limpa, com respiros generosos e legibilidade inequívoca.

A densidade de informação equilibra o rigor técnico contábil com o uso cotidiano no celular. Em repouso, as superfícies são predominantemente tonais e planas, ancoradas nos preceitos do Material Design 3. O calor visual surge em detalhes humanos sutis: cantos confortavelmente arredondados (12px a 16px), micro-interações responsivas ao toque e uma paleta primária baseada em ciano e petróleo que transmite estabilidade e lucidez mental.

**Anti-referências visuais confirmadas:**
- Estética de "cassino/cripto": fundos pretos absolutos com gráficos de candlestick neon, badges piscantes ou apelo à urgência/especulação.
- Frieza de planilha de auditoria: tabelas sobrecarregadas de bordas pesadas e números sem hierarquia tipográfica.
- Gamificação infantilizada: avatares cartunescos, confetes coloridos e mensagens condescendentes que mascaram a seriedade do orçamento familiar.

**Key Characteristics:**
- **Clareza Tonal:** Profundidade construída por hierarquia de superfícies e bordas delicadas, mantendo sombras restritas a camadas ativas/flutuantes.
- **Rigor Tipográfico Numérico:** Uso estrito de tipografia com alinhamento tabular (`Roboto Mono`) para quantias monetárias, alinhando centavos perfeitamente.
- **Eficiência no Toque:** Ergonomia pensada para uso com uma mão no smartphone através de bottom sheets e touch targets generosos de 48px.

## Colors

A paleta de cores reflete tranquilidade e equilíbrio, reservando contrastes pontuais para feedback semântico estrito.

### Primary
- **Cyan Equilíbrio** (`#00838f`): Cor de identidade primária. Utilizada na toolbar superior, botões de ação principal, estados ativos de navegação e indicadores de destaque institucional. Comunica lucidez e calma nas decisões de dinheiro.
- **Cyan Profundo** (`#006064`): Variante de contraste reforçado para estados de hover e active de botões primários.
- **Cyan Container** (`#e0f7fa`): Fundo suave para áreas selecionadas e chips de contexto ativo.

### Tertiary
- **Âmbar Acolhedor** (`#ef6c00`): Tom quente e construtivo, utilizado com estrita moderação para itens realizados no planejamento mensal e badges de confirmação positiva sem peso de urgência.
- **Âmbar Container** (`#ffe0b2`): Fundo suave de apoio para elementos destacados pelo tom terciário.

### Neutral
- **Superfície Base** (`#ffffff`): Fundo padrão de cartões, painéis de detalhe e tabelas.
- **Superfície Nível Baixo** (`#fafafa`): Canvas de fundo geral da aplicação no desktop.
- **Superfície Variante** (`#f5f5f5`): Fundos de containers secundários, badges neutros e estados desabilitados.
- **Texto Primário (On-Surface)** (`#1c1b1f` / `#333333`): Contraste máximo para títulos, descrições e valores principais.
- **Texto de Suporte (On-Surface-Variant)** (`#595959` / `#757575`): Rótulos, datas, metadados e legendas (contraste mínimo 4.5:1 / 7:1 atendendo WCAG AA).
- **Divisores e Contornos** (`#e0e0e0` / `var(--mat-sys-outline-variant)`): Linhas de 1px para delimitar cartões sem poluir o campo de visão.

### Semantic Status
- **Receita / Pago (Verde Seguro)** (`#2e7d32` / fundo `#e8f5e9`): Entradas confirmadas e status quitado.
- **Despesa / Alerta (Rubro Cautela)** (`#c62828` / fundo `#ffebee`): Saídas de caixa, saldos devedores de faturas e erros.
- **Transferência / Info (Azul Movimento)** (`#1565c0` / fundo `#e3f2fd`): Movimentações double-entry e notas informativas.
- **Pendente / Projeção (Âmbar Atenção)** (`#f57f17` / fundo `#fff8e1`): Transações projetadas (fantasmas RRULE), parcelas em aberto e revisões de extrato.

### Named Rules
**The Rarity of Accent Rule.** A cor primária (Cyan) deve ocupar no máximo 10% a 15% de qualquer superfície de tela. Sua autoridade vem do respiro oferecido pelas superfícies neutras.
**The Absolute Semantic Invariant Rule.** Verde e vermelho são reservados exclusivamente para receita/despesa ou sucesso/erro. Jamais utilize a cor da marca (Cyan) para substituir sinais financeiros de débito ou crédito.

## Typography

**Display Font:** Roboto (com fallback em `-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`)
**Body Font:** Roboto (com fallback em `-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`)
**Label/Mono Font:** 'Roboto Mono' (com fallback em `ui-monospace, monospace`)

**Character:** Uma combinação limpa, amigável e legível entre o humanismo funcional do Roboto e a precisão técnica da Roboto Mono em números financeiros.

### Hierarchy
- **Display** (600, 1.75rem / 28px, line-height 1.2, letter-spacing -0.02em): Títulos principais de páginas como Dashboard e Linha do Tempo.
- **Headline** (600, 1.35rem / 22px, line-height 1.3): Títulos de cartões de resumo, cards de onboarding e modais.
- **Title** (600, 1rem / 16px, line-height 1.4): Títulos de seções, descrições de transações na listagem e grupos de faturas.
- **Body** (400 / 500, 0.875rem / 14px, line-height 1.5): Textos explicativos, formulários, tabelas e avisos informativos.
- **Label** (600, 0.75rem / 12px, letter-spacing 0.06em, uppercase): Subheaders de navegação, cabeçalhos de tabela compactos e chips de status.
- **Numeric Figures** (600, 0.95rem - 1.5rem, 'Roboto Mono'): Saldos, valores monetários (`.amount`), totalizadores e taxas.

### Named Rules
**The Tabular Number Rule.** Toda quantia monetária exibida no sistema deve obrigatoriamente utilizar `'Roboto Mono'` com alinhamento à direita em tabelas ou blocos comparativos, assegurando alinhamento decimal exato.

## Layout

O layout segue o modelo de caixa fluida responsiva ancorado em três zonas de corte:
- **Compact (< 720px):** Otimizado para smartphones em portrait. A navegação passa para a barra inferior fixa (`app-bottom-tab-bar`), os cartões de resumo viram carrossel ou pilha única (1 coluna) e ações de filtro abrem em bottom sheets deslizantes.
- **Medium (720px a 1023.98px):** Tablets e orientação landscape. Grid em duas colunas para resumos e navegação via gaveta lateral retrátil.
- **Expanded (>= 1024px):** Desktop pleno. Largura máxima de conteúdo travada em 1280px (`margin: 0 auto`), toolbar fixa de 64px, menu lateral persistente e grid em 3 ou 4 colunas.

### Spacing Rhythm
- `--space-xs` (4px): Micro-ajustes de ícones, tags e espaçamentos internos de badges.
- `--space-sm` (8px): Gaps entre botões de ação e campos compactos.
- `--space-md` (16px): Padding canônico de cartões, formulários e grids mobile.
- `--space-lg` (24px): Padding externo no desktop e espaçamento vertical entre grandes blocos.
- `--space-xl` (32px): Margem superior de títulos principais e estados vazios.
- `--space-2xl` (48px): Piso mínimo de toque para interações manuais.

### Named Rules
**The Touch-Floor Rule.** Qualquer elemento clicável ou acionável em telas touch deve possuir altura mínima garantida de 48px (`var(--touch-target-min, 48px)`), mesmo que a sua representação visual seja menor (usando padding invisível).

## Elevation & Depth

O sistema é predominantemente **flat e tonal**. A diferenciação entre planos apoia-se nas variações de tom de superfície do Material Design 3 e em contornos sutis de 1px (`#e0e0e0`).

Sombras decorativas são terminantemente proibidas sobre superfícies em repouso. Sombras existem exclusivamente como resposta física a elevação flutuante sobreposta ao conteúdo:

### Shadow Vocabulary
- **Floating Bar** (`box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.08)`): Utilizada exclusivamente na barra inferior fixa de navegação móvel (`.tab-bar`).
- **Modal Sheet / Quick Entry** (`box-shadow: 0 8px 32px rgba(0, 0, 0, 0.16)`): Utilizada em bottom sheets de lançamento rápido e diálogos de confirmação sobrepostos à tela.

### Named Rules
**The Rest-is-Flat Rule.** Superfícies de cartões, painéis e tabelas permanecem planas em repouso. Elevações e sombras surgem exclusivamente quando um elemento se destaca do plano base (modais, menus suspensos e bottom sheets).

## Shapes

O vocabulário formal combina cantos confortáveis com solidez geométrica:
- **Micro (4px):** Indicadores de parcela e barras de progresso lineares.
- **Pequeno (8px):** Campos de entrada de texto e botões secundários em telas compactas.
- **Médio (12px):** Badges de status, abas de filtro e containers de tabelas de extrato.
- **Grande (16px):** Cartões de resumo métrico do Dashboard, cards de ciclo e blocos de boas-vindas do onboarding.
- **Pílula Completa (28px):** Itens selecionados da navegação lateral (`.active-link`) e botões de ação primária.
- **Topo de Bottom Sheet (24px 24px 0 0):** Arredondamento característico superior dos sheets móveis.
- **Wrapper de Ícones de Categoria (10px):** Formato arredondado para caixas de ícones coloridos de despesas e receitas.

## Components

### Buttons
- **Shape:** Pílula completa (`border-radius: 28px`) para ações primárias; cantos suaves (8px a 12px) para botões utilitários.
- **Primary:** Fundo Cyan Equilíbrio (`#00838f`), texto branco, padding `10px 24px`, fonte Roboto medium 14px.
- **Hover / Focus:** Transição suave para Cyan Profundo (`#006064`) e outline nítido de 2px com offset de 2px no foco via teclado.
- **Quick-Entry ("+"):** Botão central de destaque na bottom tab bar com ícone evidente `add_circle` para acionamento com o polegar.

### Cards / Containers
- **Corner Style:** 16px (`--rounded-lg`) para cartões de métrica e ciclos; 12px para tabelas.
- **Background:** Branco puro (`#ffffff`) sobre canvas neutro (`#fafafa`).
- **Border:** 1px sólido em tom de contorno suave (`#e0e0e0`).
- **Internal Padding:** 16px no mobile (`--space-md`) a 24px no desktop (`--space-lg`).

### Badges e Chips
- **Style:** Fundo suave com opacidade (10-15%) da cor semântica e texto em tom escuro de alto contraste (ex.: verde `#e8f5e9` com texto `#2e7d32`).
- **Shape:** Cantos de 12px, padding `3px 10px`, texto em caixa alta de 11-12px (`0.75rem`) com letter-spacing de 0.4px.

### Inputs / Fields
- **Style:** Campos baseados no Angular Material 3 com contorno nítido e rótulo flutuante.
- **Focus:** Contorno ativo em Cyan Equilíbrio (`#00838f`) de 2px.
- **Formatadores Específicos:** Inputs monetários com prefixo fixo "R$" e máscara em pt-BR.

### Navigation
- **Desktop Sidenav:** 220px de largura fixa, itens com 48px de altura mínima, item ativo em pílula com fundo `var(--mat-sys-secondary-container)` e ícones expressivos.
- **Mobile Bottom Tab Bar:** Barra fixa com 4 destinos principais (Início, Transações, "+" Lançamento Rápido, Planejamento), labels compactos (11px) e feedback tátil.

### Signature Component: Quick Entry Bottom Sheet
O coração operacional do produto no mobile. Aberto a partir do botão "+" da barra inferior:
- Ocupa a base da tela com cantos superiores arredondados em 24px.
- Campo de valor de alto destaque em fonte de grande porte (24-32px).
- Seletores rápidos de conta e categoria acionáveis com toques únicos.
- Botão "Salvar" no rodapé com alcance fácil do polegar.

## Do's and Don'ts

### Do:
- **Do** formatar todas as quantias monetárias com o símbolo "R$", separador de milhar por ponto e decimal por vírgula em tipografia monospaçada (`Roboto Mono`).
- **Do** manter a meta de toque mínimo de 48px (`var(--touch-target-min)`) em todos os elementos clicáveis no mobile.
- **Do** usar cores semânticas (verde para receita/pago, vermelho para despesa/atrasado) com fundos correspondentes suaves para garantir contraste acessível (WCAG AA).
- **Do** reservar o uso de sombras exclusivamente para camadas elevadas (bottom sheets, diálogos e barras flutuantes).
- **Do** exibir sempre a proveniência e estado de revisão em lançamentos importados antes do commit final.

### Don't:
- **Don't** utilizar fundos pretos neon, gráficos fluorescentes ou tipografia espalhafatosa no estilo "cripto/trader".
- **Don't** aplicar drop shadows em cartões em repouso sobre o fundo da página.
- **Don't** hardcodar cores no CSS; referencie sempre variáveis e tokens semânticos do Material 3 e de `_tokens.scss`.
- **Don't** utilizar caixas de diálogo modais centralizadas no mobile quando uma bottom sheet for o padrão natural de interação com o polegar.
- **Don't** esconder o botão de Quick Entry ("+") atrás de menus secundários no smartphone.
