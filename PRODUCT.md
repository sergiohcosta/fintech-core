# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
Casais e famílias gerindo o orçamento doméstico em conjunto, com papéis de administração e membros convidados no mesmo tenant. Usuários que precisam de clareza diária sobre o caixa disponível e alívio do estresse gerado por faturas de cartão de crédito e parcelamentos futuros acumulados.

## Product Purpose
Prover uma plataforma financeira colaborativa que une rigor contábil com simplicidade de uso diário. O produto existe para eliminar a incerteza financeira do fim do mês ("quanto realmente podemos gastar hoje?") através de previsibilidade exata de faturas, projeção on-the-fly de despesas recorrentes e cálculo em tempo real do limite diário seguro de gastos (`dailyAllowance`). Sucesso significa o casal olhar para o painel em menos de 1 minuto e saber com precisão sua saúde financeira e limites imediatos.

## Positioning
Diferente de planilhas frágeis ou aplicativos financeiros comuns que apenas somam despesas retroativamente e alucinam saldos, o Nosso Dinheirinho oferece contabilidade de dupla entrada (double-entry em transferências), separação inequívoca entre liquidez imediata (`countInLiquidBalance`) e patrimônio (`countInNetWorth`), tratamento exato de parcelamento de cartão com fechamento/lock pessimista de faturas, e pipeline de importação bancária determinística (PDF, OFX, CSV) com privacidade local e área de conferência (staging) antes de qualquer lançamento no extrato oficial.

## Operating Context
- **Dispositivos:** Aplicação web responsiva com foco prioritário em uso mobile (PWA, mobile-first, bottom sheets para ações rápidas de registro "+") e uso desktop para conferências detalhadas e planejamento mensal.
- **Rotinas:** Lançamento rápido de despesas no ponto de venda ("Quick Entry"), conferência de faturas na data de fechamento/vencimento, e planejamento mensal do ciclo de gastos (Budget Cycle) no início de cada mês ou dia base de corte.
- **Entrada de Documentos:** Importação de extratos e faturas em PDF bancário (Itaú, Nubank), arquivos OFX de bancos brasileiros, arquivos CSV e comprovantes (Pix/recibos).

## Capabilities and Constraints
- **Multi-Tenancy:** Isolamento rígido de dados por Tenant com RLS e validação de contexto em todas as queries e staging.
- **Ciclo de Faturas e Cartão:** Faturas com ciclo explícito (`OPEN` -> `CLOSED` -> `PAID`), lock pessimista em concorrência, descarte de parcelas em faturas já fechadas/pagas (para compras parceladas manuais), e compensação de resíduo de centavos na última parcela.
- **Motor de Recorrência (RFC 5545 RRULE):** Projeção on-the-fly de despesas recorrentes sem poluição de banco de dados; materialização apenas sob confirmação explícita ou vinculação ao ciclo de planejamento.
- **Planejamento Mensal (Budget Cycles):** Cálculo dinâmico de `openingBalance` (caixa líquido histórico), `currentBalance` (caixa real atual de transações PAID), `availableToSpend` (saldo projetado seguro) e `dailyAllowance` (teto diário dividido pelos dias restantes).
- **Importação com Staging:** Nenhuma extração bruta vai direto para a base contábil oficial; toda extração cai em staging (`staged_transactions`) para revisão, ajuste e commit explícito pelo usuário.
- **Privacidade e LGPD:** Processamento prioritário via extratores determinísticos locais (Apache PDFBox, OFX, CSV) e modelos locais no homelab para dados bancários sensíveis.

## Brand Commitments
- **Nome:** Nosso Dinheirinho.
- **Voz e Tom:** Tranquilizadora, precisa, transparente e acolhedora. Evita jargões contábeis hostis aos usuários leigos (ex.: substitui termos técnicos por conceitos acionáveis como "Mapeamento de Categorias", "Disponível para Gastar", "Limite Diário").

## Evidence on Hand
- Código de produção v0.7.0 rodando com suíte de testes automatizados (backend JUnit/Spring Boot e frontend Vitest).
- Regras de negócio e contratos de API totalmente especificados em `summary.md`.
- Planejamento estratégico de lançamento de MVP em `docs/plano-estrategico-lancamento-mvp.md`.
- Plano de monetização e modelo freemium/piloto em `docs/commercialization-plan.md`.
- Roadmap de extração e conciliação em `docs/roadmap-extracao-e-conciliacao.md`.
- Seeds e fixtures de dados de importação bancária real (Itaú e Nubank) para validação de extração.

## Product Principles
1. **Verdade Contábil Absoluta:** O saldo refletido deve ser exato; nenhuma transação é adivinhada ou inserida sem respaldo e conferência humana.
2. **Previsibilidade sem Ansiedade:** O usuário deve saber instantaneamente o quanto pode gastar hoje sem comprometer faturas futuras ou compromissos recorrentes.
3. **Privacidade Bancária por Padrão:** Dados de extratos e comprovantes contêm informações altamente sensíveis e devem ser processados com máxima privacidade local.
4. **Agilidade no Ponto de Contato:** Registrar um gasto ou consultar o limite deve levar poucos segundos no celular, sem atritos ou navegações burocráticas.

## Accessibility & Inclusion
- Interface em português do Brasil (pt-BR) com formatação monetária e de data nacional.
- Targets de toque mínimos de 48px para uso confortável em telas touch e dispositivos móveis (`--touch-target-min`).
- Suporte a navegação por teclado e componentes baseados no Angular Material 3 com contraste semântico.
