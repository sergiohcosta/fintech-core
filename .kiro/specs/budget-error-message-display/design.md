# Budget Error Message Display — Bugfix Design

## Overview

Four error callbacks in `BudgetCycleCurrentComponent` (`addItem`, `linkTransaction`, `unlinkTransaction`, `deleteItem`) use parameterless arrow functions `() =>`, discarding the `HttpErrorResponse` and always showing a generic "Erro. Tente novamente." message. The fix captures the error parameter and reads `err.error?.message`, falling back to the generic string when no backend message exists. The pattern is already proven in the same file's `openCycle()` method.

## Glossary

- **Bug_Condition (C)**: An HTTP error response with a JSON body containing a `message` field is returned during one of the four affected methods
- **Property (P)**: The snackbar displays `err.error.message` instead of the hardcoded generic string
- **Preservation**: Success paths and the `openCycle`/`closeCycle` error paths remain unchanged
- **HttpErrorResponse**: Angular's typed HTTP error object; `err.error` holds the deserialized response body

## Bug Details

### Bug Condition

The bug manifests when the backend returns an error (HTTP 4xx/5xx) with a JSON body `{ "message": "..." }` during `addItem()`, `linkTransaction()`, `unlinkTransaction()`, or `deleteItem()`. The error callbacks use `() =>` (no parameter) so the response is never captured.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type { method: string, httpError: HttpErrorResponse }
  OUTPUT: boolean

  RETURN input.method IN ['addItem', 'linkTransaction', 'unlinkTransaction', 'deleteItem']
         AND input.httpError.error?.message IS NOT NULL
END FUNCTION
```

### Examples

- `addItem()` fails with 400 `{ "message": "Data deve estar dentro do período do ciclo." }` → user sees generic "Erro. Tente novamente." instead of the specific message
- `linkTransaction()` fails with 422 `{ "message": "Transação já vinculada a outro item." }` → user sees generic message
- `deleteItem()` fails with 409 `{ "message": "Item já realizado não pode ser excluído." }` → user sees generic message
- `addItem()` fails with network error (no body) → user correctly sees "Erro. Tente novamente." (fallback, not a bug)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Success callbacks for all four methods remain identical
- `openCycle()` error handling already uses the correct pattern — must not be touched
- `closeCycle()` error handling shows its own specific message — must not be touched
- `loadCurrentCycle()` error handling remains unchanged

**Scope:**
All inputs where `isBugCondition` is false (success responses, errors without a message field, other methods) are unaffected.

## Hypothesized Root Cause

The error callbacks were written as `error: () =>` instead of `error: (err: HttpErrorResponse) =>`. This is a copy-paste shortcut that was not updated to match the pattern established in `openCycle()`.

## Correctness Properties

Property 1: Bug Condition - Backend Error Message Displayed

_For any_ HTTP error response where `err.error?.message` is a non-null string during `addItem()`, `linkTransaction()`, `unlinkTransaction()`, or `deleteItem()`, the fixed code SHALL display that message in the snackbar.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

Property 2: Preservation - Fallback and Success Behavior

_For any_ HTTP error response where `err.error?.message` is null/undefined (no backend message), the fixed code SHALL display "Erro. Tente novamente." as before. For all success responses, the fixed code SHALL produce the same result as the original code.

**Validates: Requirements 2.5, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

## Fix Implementation

### Changes Required

**File**: `frontend/src/app/features/planning/budget-cycle-current/budget-cycle-current.ts`

**Functions**: `addItem()`, `linkTransaction()`, `unlinkTransaction()`, `deleteItem()`

**Specific Changes**:
Replace each `error: () =>` callback with:
```typescript
error: (err: HttpErrorResponse) => {
  const msg = err.error?.message ?? 'Erro. Tente novamente.';
  this.snackBar.open(msg, 'OK', { duration: 3000 });
}
```

No new imports needed — `HttpErrorResponse` is already imported.

## Testing Strategy

### Validation Approach

Extract the message-resolution logic into a pure helper function (`extractErrorMessage`) and test it with Vitest. This avoids TestBed complexity and follows the project's testing convention for pure-logic functions.

### Exploratory Bug Condition Checking

**Goal**: Demonstrate that the current code discards backend messages.

**Test Plan**: Write a pure function test that mimics the UNFIXED behavior (always returns the generic string regardless of input). This confirms the bug pattern exists.

**Test Cases**:
1. Input with `err.error.message = "Data inválida"` → unfixed logic returns "Erro. Tente novamente." (FAILS expected behavior)

### Fix Checking

**Goal**: Verify that for all inputs where `err.error?.message` is truthy, the helper returns that message.

**Pseudocode:**
```
FOR ALL err WHERE err.error?.message IS NOT NULL DO
  result := extractErrorMessage(err)
  ASSERT result = err.error.message
END FOR
```

### Preservation Checking

**Goal**: Verify that when `err.error?.message` is null/undefined, the helper returns the fallback.

**Pseudocode:**
```
FOR ALL err WHERE err.error?.message IS NULL DO
  result := extractErrorMessage(err)
  ASSERT result = 'Erro. Tente novamente.'
END FOR
```

### Unit Tests

- Test `extractErrorMessage` with a message present → returns the message
- Test `extractErrorMessage` with `error: null` → returns fallback
- Test `extractErrorMessage` with `error: {}` (no message key) → returns fallback
- Test `extractErrorMessage` with `error.message = ""` (empty string) → returns fallback

### Property-Based Tests

- Generate random non-empty strings as `err.error.message` → helper always returns that string
- Generate random falsy values (null, undefined, "", 0) → helper always returns fallback

### Integration Tests

- Not required for this fix — behavior is fully covered by the pure helper test
