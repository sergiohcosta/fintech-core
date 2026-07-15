# **Especificação de Produto (PRD): Motor de Recorrência Unificado**

## **1\. Visão Geral e Objetivo**

O **Motor de Recorrência Unificado** é o serviço central (Core) responsável por interpretar, projetar e gerenciar regras de repetição temporal financeira dentro do SaaS.

Ele atua como a "máquina do tempo" do aplicativo. Em vez de exigir que o usuário cadastre manualmente 48 parcelas de um carro, o sistema armazena uma única **Regra**, que é dinamicamente interpretada para prever o fluxo de caixa futuro na Vida Real e alimentar o simulador "E se...?".

## **2\. Princípios Arquiteturais**

### **2.1 Separação entre "Regra" e "Transação"**

Para evitar inchaço no banco de dados e garantir flexibilidade:

* **A Regra (Blueprint):** É a definição do padrão temporal (Ex: "Cobrar R$ 50 todo dia 15, mensalmente, para sempre").  
* **A Transação (Fato):** É o evento materializado e imutável gravado no banco de dados para um mês específico (Ex: "Netflix \- 15 de Junho \- R$ 50").  
* **Princípio de Projeção:** As Transações futuras **não** devem ser gravadas antecipadamente no banco de dados. Elas devem ser *projetadas em memória* (on-the-fly) pelo sistema. Apenas transações confirmadas pelo usuário são materializadas no banco de dados.

### **2.2 Mutabilidade e Desvinculação (O Problema da Netflix)**

A vida muda. Se a assinatura da Netflix de R$ 50 passar a custar R$ 55 em Agosto:

* Não podemos alterar a "Regra" original retroativamente, senão os relatórios de Janeiro a Julho ficarão errados.  
* **Solução:** O sistema deve suportar a edição de uma regra com o conceito de *"Aplicar apenas a esta e às próximas"*, o que encerra a Regra A (data de fim) e cria uma Regra B invisível para o usuário na interface, mantendo o histórico de auditoria intacto.

## **3\. Classificação de Padrões de Recorrência**

O motor deve suportar três arquétipos principais:

1. **Infinita (Assinaturas / Salários Fixos):**  
   * *Comportamento:* Repete-se até que o usuário execute uma ação manual de "Pausar" ou "Cancelar".  
2. **Finita por Parcela (Financiamentos / Compras Parceladas):**  
   * *Comportamento:* Possui um limite rígido de repetições (Ex: 1 de 12, 2 de 12... 12 de 12). Chegando ao fim, a regra expira automaticamente.  
3. **Finita por Data (Contratos com Fim Programado):**  
   * *Comportamento:* Repete-se até alcançar uma end\_date específica (Ex: "Pagar aluguel até o fim do contrato em Dezembro de 2027").

## **4\. Requisitos Funcionais (FRs)**

### **\[FR-01\] Tratamento de Exceções de Calendário (End-of-Month Capping)**

* **O Problema:** Uma regra agendada para o dia 31 falhará em Fevereiro, Abril, Junho, etc.  
* **A Regra de Negócio:** Se a regra cai no dia 29, 30 ou 31, e o mês corrente não possui esse dia, o motor deve "ancorar" a projeção da transação no **último dia útil/válido do mês** (Ex: 28 de Fevereiro).

### **\[FR-02\] Edição de Transação Isolada (Desvinculação/Detach)**

* **Comportamento:** O usuário acessa a "transação fantasma" de Energia Elétrica (que é uma regra recorrente) de Junho e edita o valor ao confirmá-la, pois a conta veio mais cara neste mês específico.  
* **Ação do Motor:** O sistema grava a transação no banco de dados marcando-a como *desvinculada do valor matriz*, mas a regra original permanece intacta projetando o valor padrão para Julho em diante.

### **\[FR-03\] Pausa e Retomada (Sleep Mode)**

* **Comportamento:** O usuário tranca a matrícula da academia por 3 meses.  
* **Ação do Motor:** A regra ganha um status paused\_until ou registra os meses a serem ignorados. A projeção volta a desenhar as "linhas fantasma" automaticamente a partir do mês de retorno, sem necessidade de recriar a regra.

### **\[FR-04\] Materialização Orientada pelo Usuário (Transações Fantasma)**

* **O Problema da Automação (Cronjob):** Materializar transações automaticamente em background gera um saldo irreal no sistema caso a transação não tenha ocorrido de fato no banco do usuário (ex: falha no cartão, esquecimento do pagamento).  
* **Comportamento Esperado (Linhas Fantasma):** Ao acessar o sistema e visualizar o mês atual ou futuro, o frontend solicita ao motor as projeções baseadas nas regras ativas. O sistema renderiza na lista de transações as "Linhas Fantasma" (visualmente distintas, ex: itálico, ícone específico, opacidade reduzida).  
* **Ação de Materialização:** O usuário deve ativamente clicar em "Confirmar" (efetivar a transação, podendo ajustar o valor final antes de salvar) ou "Pular/Ignorar". O ato de confirmar é o que executa o INSERT na tabela de transações reais.

## **5\. Modelagem de Dados Relacional (Entity-Relationship)**

### **Tabela: recurrence\_rules**

Guarda as definições atemporais (As Regras).

* id (UUID, Primary Key)  
* user\_id (UUID, Foreign Key)  
* title (String) \- Ex: "Aluguel"  
* base\_amount (Decimal)  
* frequency (Enum) \- daily, weekly, monthly, yearly  
* interval (Integer) \- Ex: 1 (todo mês), 3 (a cada 3 meses / trimestral)  
* anchor\_day (Integer) \- Dia do mês para a cobrança (1 a 31\)  
* type (Enum) \- infinite, installments, until\_date  
* total\_installments (Integer, Nullable) \- Usado se type \= installments  
* end\_date (Date, Nullable) \- Usado se type \= until\_date  
* status (Enum) \- active, paused, cancelled, completed

### **Tabela: transactions**

Guarda a realidade financeira contábil (Os Fatos). Só recebe dados após a confirmação do usuário.

* id (UUID, Primary Key)  
* user\_id (UUID, Foreign Key)  
* recurrence\_rule\_id (UUID, Foreign Key, Nullable) \- Se preenchido, indica que a transação foi originada de uma regra. Se nulo, é compra avulsa.  
* amount (Decimal) \- O valor real consolidado no momento da confirmação.  
* date (Date) \- Data efetiva em que o usuário atestou a ocorrência.  
* installment\_number (Integer, Nullable) \- Ex: 4 (para representar a consolidação da parcela 4 de X)  
* is\_detached\_from\_rule (Boolean) \- true se o usuário confirmou a transação alterando o valor base projetado pela regra.

## **6\. Integração com o Simulador "E se...?"**

A arquitetura garante que o simulador de cenários consuma o Motor de Recorrência de forma nativa.

1. **Criação de Cenário:** Ao simular a "Compra de um Carro", o simulador projeta regras temporárias atreladas a um scenario\_id.  
2. **Projeção Híbrida:** A função geradora do gráfico lê o histórico da tabela transactions, projeta as "linhas fantasma" futuras baseadas nas recurrence\_rules oficiais, e soma isso às regras do cenário ativo. Tudo processado on-the-fly.  
3. **Adoção (Merge):** Quando o usuário clica em "Adotar Cenário", o backend converte as regras do cenário em recurrence\_rules oficiais da conta. Isso fará com que, nos meses correspondentes, essas novas regras apareçam como "Linhas Fantasma" aguardando confirmação do usuário na Vida Real.

## **7\. Casos de Teste (Edge Cases para QA)**

* **Teste A (Fevereiro Bissexto):** Criar regra para o dia 31 de Janeiro. Verificar se a "linha fantasma" aparece no dia 28 (ou 29\) de Fevereiro, e retorna para o dia 31 em Março.  
* **Teste B (Inflação/Edição Pontual):** Regra de R$ 100/mês. O usuário confirma a "linha fantasma" de Agosto alterando o valor para R$ 150 (materializando a transação). Validar se a projeção fantasma para Setembro retorna para os R$ 100 originais da regra.  
* **Teste C (Ignorar Transação):** Regra ativa. O usuário clica em "Pular" na linha fantasma do mês vigente. Validar se o sistema não materializa a transação neste mês, mas continua exibindo a linha fantasma no mês seguinte.