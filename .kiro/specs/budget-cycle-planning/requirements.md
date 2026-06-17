# Requirements Document

## Introduction

Este documento especifica os requisitos para a funcionalidade de planejamento de ciclo orçamentário mensal no Fintech Core. O ciclo orçamentário permite que o usuário crie períodos personalizados (não necessariamente calendáricos) para planejar receitas e despesas, acompanhar a realização das previsões, e visualizar o saldo disponível para gastos variáveis no período restante. A funcionalidade evolui o módulo de orçamento existente, mantendo compatibilidade com itens recorrentes, parcelas e transações.

## Glossary

- **Ciclo_Orçamentário**: Período definido pelo usuário com data de início e data de fechamento, representando um ciclo de planejamento financeiro. Possui status OPEN ou CLOSED.
- **Item_Orçamentário**: Entrada planejada (receita ou despesa) dentro de um ciclo. Possui status PENDING, REALIZED ou SKIPPED.
- **Item_Recorrente**: Template de receita ou despesa que se repete a cada ciclo (ex: salário, aluguel). Possui dia do mês e flag de ativo.
- **Saldo_Inicial**: Valor monetário que representa o saldo de abertura do ciclo, calculado automaticamente a partir da soma dos saldos das contas com countInLiquidBalance=true do Tenant.
- **Saldo_Projetado**: Cálculo: Saldo_Inicial + soma(receitas planejadas) - soma(despesas planejadas).
- **Disponível_Para_Gastar**: Cálculo: receita prevista - despesas previstas - gastos variáveis já realizados que não estavam planejados no ciclo.
- **Mesada_Diária**: Cálculo: Disponível_Para_Gastar / dias restantes até o fechamento do ciclo.
- **Data_Fechamento**: Data final do ciclo definida pelo usuário (ex: dia 2 do mês seguinte, alinhado ao fechamento do cartão de crédito).
- **Gasto_Não_Planejado**: Transação de despesa realizada dentro do período do ciclo que não está vinculada a nenhum Item_Orçamentário do ciclo.
- **Realização**: Ação de marcar um Item_Orçamentário como REALIZED, vinculando-o a uma transação existente ou criando uma nova transação.
- **Sistema_Orçamento**: Módulo backend responsável pela lógica de negócio do planejamento de ciclo orçamentário.
- **API_Orçamento**: Camada REST que expõe os endpoints do módulo de orçamento.
- **Tenant**: Entidade de isolamento multi-tenant. Todo dado de orçamento pertence a exatamente um Tenant.

## Requirements

### Requirement 1: Criação de Ciclo Orçamentário

**User Story:** Como usuário, quero abrir um novo ciclo orçamentário com datas personalizadas, para que eu possa planejar minhas finanças em períodos alinhados ao meu fluxo de caixa real.

#### Acceptance Criteria

1. WHEN o usuário solicita a criação de um novo ciclo informando referenceMonth (formato yyyy-MM) e startDay (inteiro entre 1 e 28), THE Sistema_Orçamento SHALL calcular as datas do ciclo (se startDay=1: início no dia 1 e fim no último dia do mês de referência; senão: início no startDay do mês anterior ao de referência e fim no dia startDay-1 do mês de referência), calcular o Saldo_Inicial como a soma dos saldos das contas com countInLiquidBalance=true do Tenant, e criar um Ciclo_Orçamentário com status OPEN, as datas calculadas e o saldo calculado.
2. WHILE um Ciclo_Orçamentário com status OPEN já existir para o Tenant, THE Sistema_Orçamento SHALL rejeitar a criação de um novo ciclo e retornar erro informando que já existe um ciclo aberto.
3. IF o período calculado (startDate a endDate) sobrepõe um Ciclo_Orçamentário já existente para o Tenant, THEN THE Sistema_Orçamento SHALL rejeitar a criação e retornar erro informando conflito de período.
4. IF o campo referenceMonth não estiver no formato yyyy-MM ou o campo startDay estiver fora do intervalo 1-28, THEN THE Sistema_Orçamento SHALL rejeitar a criação e retornar erro de validação indicando o campo inválido.
5. WHEN um novo Ciclo_Orçamentário é criado com sucesso, THE Sistema_Orçamento SHALL gerar automaticamente um Item_Orçamentário para cada Item_Recorrente ativo do Tenant, com source RECURRING, status PENDING, e expectedDate calculada dentro do período do ciclo conforme a regra: se dayOfMonth >= startDay, a data cai no mês de início do ciclo; senão, cai no mês seguinte ao início do ciclo.
6. WHEN um novo Ciclo_Orçamentário é criado com sucesso, THE Sistema_Orçamento SHALL persistir o startDay informado como preferência do Tenant (budgetCycleStartDay) para uso em ciclos futuros.

### Requirement 2: Gerenciamento de Itens do Ciclo

**User Story:** Como usuário, quero adicionar, editar e remover itens de receita e despesa no meu ciclo aberto, para que o planejamento reflita minha realidade financeira atual.

#### Acceptance Criteria

1. WHILE o Ciclo_Orçamentário estiver com status OPEN, THE API_Orçamento SHALL permitir a adição de novos itens orçamentários manuais com descrição (máximo 255 caracteres, não vazia), valor (maior que zero, até 19 dígitos com 2 decimais), tipo (INCOME/EXPENSE), data esperada (dentro do intervalo entre start_date e end_date do ciclo, inclusive), categoria e conta.
2. WHILE o Ciclo_Orçamentário estiver com status OPEN, THE API_Orçamento SHALL permitir a edição de descrição, valor, data esperada, categoria e conta de itens com status PENDING, aplicando as mesmas regras de validação da adição (descrição não vazia até 255 caracteres, valor maior que zero, data esperada dentro do intervalo do ciclo).
3. WHILE o Ciclo_Orçamentário estiver com status OPEN, THE API_Orçamento SHALL permitir a remoção (exclusão) de itens com status PENDING ou SKIPPED, independente do source do item.
4. IF um usuário tentar editar ou remover um Item_Orçamentário com status REALIZED, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que itens realizados são imutáveis.
5. IF um usuário tentar adicionar um item a um Ciclo_Orçamentário com status CLOSED, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que o ciclo está fechado.
6. WHEN um item é adicionado manualmente pelo usuário, THE Sistema_Orçamento SHALL registrar o source como MANUAL.
7. IF a categoria ou conta informada não existir ou não pertencer ao Tenant do usuário autenticado, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro de validação indicando o campo inválido.
8. IF um usuário tentar editar um Item_Orçamentário com status SKIPPED, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que o item deve ser revertido para PENDING antes de ser editado.

### Requirement 3: Realização de Itens (Vinculação a Transações)

**User Story:** Como usuário, quero marcar um item orçamentário como realizado vinculando-o a uma transação real, para que meu planejamento reflita o que de fato aconteceu.

#### Acceptance Criteria

1. WHEN o usuário solicita a realização de um Item_Orçamentário com status PENDING informando o ID de uma transação existente, THE Sistema_Orçamento SHALL vincular o item à transação, atualizar o valor do item para o valor efetivo da transação vinculada e alterar o status para REALIZED.
2. WHEN o usuário solicita a realização de um Item_Orçamentário com status PENDING sem informar um ID de transação, THE Sistema_Orçamento SHALL criar uma nova transação com os dados do item (descrição, valor, tipo, categoria, conta, data esperada como data da transação) e vincular ao item com status REALIZED.
3. IF a transação informada já estiver vinculada a outro Item_Orçamentário, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que a transação já possui vínculo.
4. IF a transação informada pertencer a um Tenant diferente do ciclo, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro de acesso negado sem revelar a existência da transação.
5. IF o usuário solicitar a realização de um Item_Orçamentário cujo status não é PENDING ou cujo Ciclo_Orçamentário possui status CLOSED, THEN THE Sistema_Orçamento SHALL permitir a tentativa e rejeitar a operação retornando erro informando que apenas itens pendentes em ciclos abertos podem ser realizados (sem bloqueio preventivo na interface).
6. IF o tipo da transação informada (INCOME/EXPENSE) diferir do tipo do Item_Orçamentário, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando incompatibilidade de tipo.
7. WHEN o usuário solicita a desvinculação de um Item_Orçamentário com status REALIZED em um Ciclo_Orçamentário com status OPEN, THE Sistema_Orçamento SHALL remover o vínculo com a transação e reverter o status do item para PENDING.

### Requirement 4: Pular Item (Skip)

**User Story:** Como usuário, quero poder marcar um item planejado como "pulado" quando uma despesa ou receita prevista não vai acontecer naquele ciclo, sem precisar deletar o item.

#### Acceptance Criteria

1. WHILE o Ciclo_Orçamentário estiver com status OPEN, THE API_Orçamento SHALL permitir alterar o status de um Item_Orçamentário de PENDING para SKIPPED.
2. WHEN um item recebe status SKIPPED, THE Sistema_Orçamento SHALL omitir o valor desse item das somas de receitas e despesas planejadas nos cálculos de Saldo_Projetado e Disponível_Para_Gastar.
3. WHILE o Ciclo_Orçamentário estiver com status OPEN, THE API_Orçamento SHALL permitir reverter um item de SKIPPED para PENDING, restaurando a inclusão do seu valor nos cálculos de Saldo_Projetado e Disponível_Para_Gastar.
4. IF o usuário tentar alterar o status de um Item_Orçamentário com status REALIZED para SKIPPED, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que itens realizados não podem ser pulados.
5. IF o usuário tentar pular ou reverter um item de um Ciclo_Orçamentário com status CLOSED, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que o ciclo está fechado.

### Requirement 5: Resumo e Cálculos do Ciclo

**User Story:** Como usuário, quero ver um resumo com saldo projetado, total disponível para gastar e mesada diária, para que eu possa tomar decisões financeiras informadas durante o ciclo.

#### Acceptance Criteria

1. WHEN o usuário consulta o resumo do Ciclo_Orçamentário, THE Sistema_Orçamento SHALL calcular o Saldo_Projetado como: Saldo_Inicial + soma(valor dos itens de receita com status PENDING ou REALIZED) - soma(valor dos itens de despesa com status PENDING ou REALIZED).
2. WHEN o usuário consulta o resumo do Ciclo_Orçamentário, THE Sistema_Orçamento SHALL calcular o Disponível_Para_Gastar como: soma(valor dos itens de receita com status PENDING ou REALIZED) - soma(valor dos itens de despesa com status PENDING ou REALIZED) - soma(valor dos Gastos_Não_Planejados realizados no período do ciclo).
3. WHEN o usuário consulta o resumo do Ciclo_Orçamentário e a data atual é anterior à Data_Fechamento, THE Sistema_Orçamento SHALL calcular a Mesada_Diária sob demanda (apenas quando o resumo é solicitado) como: Disponível_Para_Gastar / número de dias restantes (Data_Fechamento - data atual, incluindo o dia atual e excluindo a Data_Fechamento), arredondando para baixo com 2 casas decimais.
4. IF a data atual for igual ou posterior à Data_Fechamento, THEN THE Sistema_Orçamento SHALL apresentar a Mesada_Diária como zero.
5. IF o Disponível_Para_Gastar for menor ou igual a zero, THEN THE Sistema_Orçamento SHALL apresentar a Mesada_Diária como zero independente dos dias restantes.
6. WHEN o usuário consulta o resumo, THE Sistema_Orçamento SHALL retornar também: total de receitas planejadas, total de despesas planejadas, total de receitas realizadas, total de despesas realizadas, total de Gastos_Não_Planejados, dias restantes até Data_Fechamento e quantidade de itens pendentes.
7. THE Sistema_Orçamento SHALL identificar Gastos_Não_Planejados consultando transações de despesa do Tenant com data dentro do período do ciclo consultado que não possuem vínculo com nenhum Item_Orçamentário desse ciclo.

### Requirement 6: Fechamento de Ciclo

**User Story:** Como usuário, quero fechar o ciclo quando o período encerrar, para que eu possa iniciar um novo ciclo com dados limpos e manter o histórico.

#### Acceptance Criteria

1. WHEN o usuário solicita o fechamento do Ciclo_Orçamentário com status OPEN, THE Sistema_Orçamento SHALL alterar o status do ciclo para CLOSED e preservar todos os itens orçamentários em seus status atuais (PENDING, REALIZED ou SKIPPED) sem alteração automática.
2. WHILE o Ciclo_Orçamentário estiver com status CLOSED, THE Sistema_Orçamento SHALL rejeitar qualquer operação de adição, edição, remoção ou alteração de status dos itens orçamentários vinculados ao ciclo, retornando erro informando que o ciclo está fechado.
3. IF o Ciclo_Orçamentário já estiver com status CLOSED, THEN THE Sistema_Orçamento SHALL rejeitar a operação de fechamento e retornar erro informando que o ciclo já está fechado.
4. WHEN o ciclo é fechado com sucesso, THE Sistema_Orçamento SHALL persistir o resumo final do ciclo (Saldo_Projetado, Disponível_Para_Gastar, total de receitas realizadas, total de despesas realizadas e total de Gastos_Não_Planejados) como snapshot imutável para consulta histórica.

### Requirement 7: Consulta de Ciclos e Histórico

**User Story:** Como usuário, quero consultar ciclos anteriores e o ciclo atual, para que eu possa analisar meu histórico de planejamento financeiro.

#### Acceptance Criteria

1. WHEN o usuário solicita a listagem de ciclos, THE API_Orçamento SHALL retornar ciclos do Tenant paginados (tamanho padrão 12, máximo 50 por página), ordenados por data de início decrescente, incluindo na resposta: conteúdo da página, número total de elementos, total de páginas e número da página atual.
2. WHEN o usuário solicita os detalhes de um Ciclo_Orçamentário específico por ID, THE API_Orçamento SHALL retornar o ciclo com todos os seus itens ordenados por data esperada crescente e o resumo calculado conforme definido no Requisito 5.
3. THE API_Orçamento SHALL filtrar todos os dados de ciclos exclusivamente pelo Tenant do usuário autenticado, extraindo o tenant_id do token JWT sem aceitar tenant_id como parâmetro de entrada.
4. IF o usuário solicitar detalhes de um Ciclo_Orçamentário com ID inexistente ou pertencente a outro Tenant, THEN THE API_Orçamento SHALL retornar erro 403 (Forbidden) em ambos os casos sem revelar se o recurso existe, e SHALL retornar 200 com os detalhes do ciclo quando o recurso existir e pertencer ao Tenant do usuário autenticado.

### Requirement 8: Itens Recorrentes

**User Story:** Como usuário, quero gerenciar meus itens recorrentes (salário, aluguel, assinatura) separadamente, para que ciclos futuros sejam pré-populados automaticamente.

#### Acceptance Criteria

1. THE API_Orçamento SHALL permitir criar itens recorrentes com: descrição (1-255 caracteres, não vazia), valor (maior que zero, até 19 dígitos com 2 decimais), tipo (INCOME/EXPENSE), dayOfMonth (1-28), categoria e conta.
2. THE API_Orçamento SHALL permitir editar itens recorrentes ativos. Alterações em itens recorrentes não afetam itens já gerados em ciclos existentes.
3. THE API_Orçamento SHALL permitir desativar (soft-delete) itens recorrentes. Itens desativados param de gerar itens em novos ciclos.
4. WHEN um item recorrente é desativado, THE Sistema_Orçamento SHALL manter o item_recorrente no banco com active=false e preservar referências em itens de ciclos anteriores.
5. THE API_Orçamento SHALL listar itens recorrentes do Tenant ordenados por descrição crescente, com filtro opcional por status (ativo/inativo).
6. IF a categoria ou conta informada na criação ou edição de item recorrente não existir ou não pertencer ao Tenant, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro de validação indicando o campo inválido.
7. IF o usuário tentar editar um item recorrente com active=false, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro informando que o item deve ser reativado antes de ser editado.

### Requirement 9: Isolamento Multi-Tenant

**User Story:** Como operador da plataforma, quero garantir que dados de orçamento sejam completamente isolados entre tenants, para que não haja vazamento de dados entre clientes.

#### Acceptance Criteria

1. THE Sistema_Orçamento SHALL incluir o filtro de tenant_id em toda operação de leitura, criação, edição e exclusão de ciclos, itens orçamentários e itens recorrentes.
2. IF um usuário tentar acessar um Ciclo_Orçamentário, Item_Orçamentário ou Item_Recorrente de outro Tenant, THEN THE Sistema_Orçamento SHALL retornar erro 403 (Forbidden) com corpo de resposta idêntico ao de recurso inexistente, sem indicar se o recurso existe.
3. THE Sistema_Orçamento SHALL extrair o tenant_id do token JWT do usuário autenticado para todas as operações, sem aceitar tenant_id como parâmetro de entrada.
4. IF o usuário autenticado não possuir associação a um Tenant válido, THEN THE Sistema_Orçamento SHALL rejeitar a operação e retornar erro 403 (Forbidden) antes de executar qualquer lógica de negócio.
