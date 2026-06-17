# Bugfix Requirements Document

## Introduction

Quando o backend retorna um erro de validação ou regra de negócio (HTTP 400/422) durante operações no planejamento orçamentário (`addItem`, `linkTransaction`, `unlinkTransaction`, `deleteItem`), o frontend exibe apenas uma mensagem genérica "Erro. Tente novamente." no snackbar, descartando a mensagem específica enviada pelo backend (ex: "Data deve estar dentro do período do ciclo."). O usuário não consegue entender o que precisa corrigir.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `addItem()` THEN the system discards the error response and displays a generic "Erro. Tente novamente." snackbar message

1.2 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `linkTransaction()` THEN the system discards the error response and displays a generic "Erro. Tente novamente." snackbar message

1.3 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `unlinkTransaction()` THEN the system discards the error response and displays a generic "Erro. Tente novamente." snackbar message

1.4 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `deleteItem()` THEN the system discards the error response and displays a generic "Erro. Tente novamente." snackbar message

### Expected Behavior (Correct)

2.1 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `addItem()` THEN the system SHALL display the backend-provided message in the snackbar

2.2 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `linkTransaction()` THEN the system SHALL display the backend-provided message in the snackbar

2.3 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `unlinkTransaction()` THEN the system SHALL display the backend-provided message in the snackbar

2.4 WHEN the backend returns an HTTP error response with a JSON body containing a `message` field during `deleteItem()` THEN the system SHALL display the backend-provided message in the snackbar

2.5 WHEN the backend returns an HTTP error response WITHOUT a `message` field (e.g., network error, unparseable body) during any of the four methods THEN the system SHALL fallback to displaying "Erro. Tente novamente." in the snackbar

### Unchanged Behavior (Regression Prevention)

3.1 WHEN `addItem()` succeeds THEN the system SHALL CONTINUE TO display "Item adicionado." in the snackbar and add the item to the list

3.2 WHEN `linkTransaction()` succeeds THEN the system SHALL CONTINUE TO update the item in the list without showing an error

3.3 WHEN `unlinkTransaction()` succeeds THEN the system SHALL CONTINUE TO update the item in the list without showing an error

3.4 WHEN `deleteItem()` succeeds THEN the system SHALL CONTINUE TO remove the item from the list without showing an error

3.5 WHEN `openCycle()` encounters an error THEN the system SHALL CONTINUE TO display the backend-provided message (existing correct behavior)

3.6 WHEN `closeCycle()` encounters an error THEN the system SHALL CONTINUE TO display "Erro ao fechar ciclo." (existing behavior, not in scope of this fix)
