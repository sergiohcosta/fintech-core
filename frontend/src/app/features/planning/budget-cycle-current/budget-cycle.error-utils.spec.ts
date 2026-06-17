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

  describe('extractErrorMessage — backend 422 messages', () => {
    it('returns backend message for realize error', () => {
      const err = { error: { message: 'Item já foi realizado.' } };
      expect(extractErrorMessage(err, FALLBACK)).toBe(err.error.message);
    });

    it('returns backend message for skip error', () => {
      const err = { error: { message: 'Item já foi pulado.' } };
      expect(extractErrorMessage(err, FALLBACK)).toBe(err.error.message);
    });

    it('returns backend message for link error', () => {
      const err = { error: { message: 'Transação já vinculada a outro item.' } };
      expect(extractErrorMessage(err, FALLBACK)).toBe(err.error.message);
    });

    it('returns backend message for delete error', () => {
      const err = { error: { message: 'Item já realizado não pode ser excluído.' } };
      expect(extractErrorMessage(err, FALLBACK)).toBe(err.error.message);
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
          (err: { error?: null | { message?: string } }) => {
            expect(extractErrorMessage(err, FALLBACK)).toBe(FALLBACK);
          },
        ),
      );
    });

    it('returns fallback when err.error has no message field', () => {
      fc.assert(
        fc.property(
          fc.record({
            error: fc.record({}, { requiredKeys: [] }).map((obj: Record<string, never>) => obj as { message?: string }),
          }),
          (err: { error: { message?: string } }) => {
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
          (err: { error?: { message?: string } | null }) => {
            expect(extractErrorMessage(err, FALLBACK)).toBe(FALLBACK);
          },
        ),
      );
    });
  });
});
