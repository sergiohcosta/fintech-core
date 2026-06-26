import { describe, it, expect } from 'vitest';
import { buildRrule, parseRrule } from './rrule-builder';

describe('rrule-builder', () => {
  it('monta mensal no dia fixo', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 15, end: { kind: 'NEVER' } })).toBe(
      'FREQ=MONTHLY;BYMONTHDAY=15',
    );
  });

  it('monta trimestral', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 3, dayOfMonth: 1, end: { kind: 'NEVER' } })).toBe(
      'FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=1',
    );
  });

  it('mapeia "último dia" para BYMONTHDAY=-1', () => {
    expect(buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 'LAST', end: { kind: 'NEVER' } })).toBe(
      'FREQ=MONTHLY;BYMONTHDAY=-1',
    );
  });

  it('inclui COUNT', () => {
    expect(
      buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 10, end: { kind: 'COUNT', count: 12 } }),
    ).toBe('FREQ=MONTHLY;BYMONTHDAY=10;COUNT=12');
  });

  it('inclui UNTIL', () => {
    expect(
      buildRrule({ frequency: 'MONTHLY', interval: 1, dayOfMonth: 5, end: { kind: 'UNTIL', date: '2027-12-31' } }),
    ).toBe('FREQ=MONTHLY;BYMONTHDAY=5;UNTIL=20271231T000000Z');
  });

  it('parse é inverso de build (round-trip)', () => {
    const form = { frequency: 'MONTHLY', interval: 2, dayOfMonth: 'LAST', end: { kind: 'NEVER' } } as const;
    expect(parseRrule(buildRrule(form))).toEqual(form);
  });

  it('parse de UNTIL volta para ISO', () => {
    expect(parseRrule('FREQ=YEARLY;BYMONTHDAY=1;UNTIL=20271231T000000Z')).toEqual({
      frequency: 'YEARLY',
      interval: 1,
      dayOfMonth: 1,
      end: { kind: 'UNTIL', date: '2027-12-31' },
    });
  });
});
