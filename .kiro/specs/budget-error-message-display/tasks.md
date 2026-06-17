# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Backend Error Message Displayed
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - Create a pure helper function `extractErrorMessage(err: { error?: { message?: string } }, fallback: string): string` in a new file `budget-cycle-current/budget-cycle.error-utils.ts`
  - The helper returns `err.error?.message || fallback`
  - Write a Vitest test file `budget-cycle-current/budget-cycle.error-utils.spec.ts`
  - Test that when `err.error.message` is `"Data inválida"`, the result equals `"Data inválida"`
  - **BUT**: in the UNFIXED code, the four callbacks never call this helper — they hardcode the generic string
  - To demonstrate the bug on unfixed code: write a test that imports the CURRENT error callback pattern (a function that ignores its argument and returns `'Erro. Tente novamente.'`) and assert it returns the backend message — this will FAIL
  - **EXPECTED OUTCOME**: Test FAILS (proves the bug exists — the unfixed pattern discards the message)
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Fallback and Success Behavior
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: when `err.error` is `null`, unfixed code shows "Erro. Tente novamente." — this is correct
  - Observe: when `err.error` is `{}` (no message field), unfixed code shows "Erro. Tente novamente." — this is correct
  - Write Vitest property-based tests (using `fast-check`) that generate random falsy/missing message values and assert the helper returns the fallback string `'Erro. Tente novamente.'`
  - Verify tests PASS on UNFIXED code (the fallback path already works correctly)
  - **EXPECTED OUTCOME**: Tests PASS (confirms baseline fallback behavior to preserve)
  - _Requirements: 2.5, 3.1, 3.2, 3.3, 3.4_

- [x] 3. Fix error message display in budget-cycle-current

  - [x] 3.1 Implement the fix
    - Create `extractErrorMessage` helper in `budget-cycle.error-utils.ts` (if not already created in task 1)
    - In `budget-cycle-current.ts`, replace the four `error: () =>` callbacks in `addItem()`, `linkTransaction()`, `unlinkTransaction()`, `deleteItem()` with `error: (err: HttpErrorResponse) => { const msg = err.error?.message ?? 'Erro. Tente novamente.'; this.snackBar.open(msg, 'OK', { duration: 3000 }); }`
    - No new imports needed — `HttpErrorResponse` is already imported
    - _Bug_Condition: isBugCondition(input) where input.method IN affected methods AND err.error?.message IS NOT NULL_
    - _Expected_Behavior: snackBar displays err.error.message_
    - _Preservation: fallback to generic message when no backend message; success paths unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4_

  - [x] 3.2 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Backend Error Message Displayed
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - Run `npx vitest --run budget-cycle.error-utils.spec.ts`
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 3.3 Verify preservation tests still pass
    - **Property 2: Preservation** - Fallback and Success Behavior
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run `npx vitest --run budget-cycle.error-utils.spec.ts`
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions)

- [x] 4. Checkpoint - Ensure all tests pass
  - Run `npm test` from the `frontend/` directory
  - Ensure all tests pass, ask the user if questions arise
