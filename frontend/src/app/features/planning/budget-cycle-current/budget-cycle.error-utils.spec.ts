import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { extractErrorMessage } from './budget-cycle.error-utils';

const FALLBACK = 'Erro. Tente novamente.';

describe('budget-cycle error handling', () => {
  describe('extractErrorMessage (helper)', () => {
    it('returns backend message when present', () => {
      const err = { error: { message: 'Data inválida' } };
      expect(extractErrorMessage(err, FALLBACK)).toBe('Data inválida');
    });
  });

  describe('Bug Condition: unfixed error callback pattern discards backend message', () => {
    /**
     * Validates: Requirements 1.1, 1.2, 1.3, 1.4
     *
     * The UNFIXED code uses `error: () => ...` — a parameterless callback
     * that ignores the HttpErrorResponse and always returns the generic string.
     * This test mimics that pattern and asserts it should return the backend message.
     * It MUST FAIL on unfixed code, proving the bug exists.
     */
    // ponytail: simulates the unfixed callback signature `error: () => 'Erro. Tente novamente.'`
    const unfixedErrorCallback = (_err: unknown) => FALLBACK;

    it('unfixed addItem callback should return backend message (EXPECTED TO FAIL)', () => {
      const err = { error: { message: 'Data deve estar dentro do período do ciclo.' } };
      const result = unfixedErrorCallback(err);
      expect(result).toBe(err.error.message);
    });

    it('unfixed linkTransaction callback should return backend message (EXPECTED TO FAIL)', () => {
      const err = { error: { message: 'Transação já vinculada a outro item.' } };
      const result = unfixedErrorCallback(err);
      expect(result).toBe(err.error.message);
    });

    it('unfixed unlinkTransaction callback should return backend message (EXPECTED TO FAIL)', () => {
      const err = { error: { message: 'Item não está vinculado.' } };
      const result = unfixedErrorCallback(err);
      expect(result).toBe(err.error.message);
    });

    it('unfixed deleteItem callback should return backend message (EXPECTED TO FAIL)', () => {
      const err = { error: { message: 'Item já realizado não pode ser excluído.' } };
      const result = unfixedErrorCallback(err);
      expect(result).toBe(err.error.message);
    });
  });

  describe('Property 2: Preservation — Fallback Behavior', () => {
    /**
     * Validates: Requirements 2.5, 3.1, 3.2, 3.3, 3.4
     *
     * When err.error?.message is falsy or missing, extractErrorMessage
     * must return the fallback string. This path already works correctly
     * in the unfixed code — these tests confirm the baseline to preserve.
     */

    it('returns fallback when err.error is nullish', () => {
      // ponytail: covers null, undefined, and missing error field
      fc.assert(
        fc.property(
          fc.oneof(
            fc.constant({ error: null }),
            fc.constant({ error: undefined }),
            fc.constant({} as { error?: { message?: string } }),
          ),
          (err) => {
            expect(extractErrorMessage(err, FALLBACK)).toBe(FALLBACK);
          },
        ),
      );
    });

    it('returns fallback when err.error has no message field', () => {
      fc.assert(
        fc.property(
          fc.record({
            error: fc.record({}, { requiredKeys: [] }).map((obj) => obj as { message?: string }),
          }),
          (err) => {
            expect(extractErrorMessage(err, FALLBACK)).toBe(FALLBACK);
          },
        ),
      );
    });

    it('returns fallback when err.error.message is a falsy value', () => {
      fc.assert(
        fc.property(
          fc.oneof(
            fc.constant({ error: { message: '' } }),
            fc.constant({ error: { message: undefined } }),
            fc.constant({ error: { message: null as unknown as string | undefined } }),
          ),
          (err) => {
            expect(extractErrorMessage(err, FALLBACK)).toBe(FALLBACK);
          },
        ),
      );
    });
  });
});
