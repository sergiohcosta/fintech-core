import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  DEFAULT_TIMELINE_FILTERS,
  currentMonthTimelineFilters,
  loadTimelineFilters,
  saveTimelineFilters,
  TIMELINE_STORAGE_KEY,
} from './transaction-timeline.filters';

describe('transaction-timeline.filters', () => {
  beforeEach(() => localStorage.clear());

  it('default tem viewMode calendar e listas vazias', () => {
    expect(DEFAULT_TIMELINE_FILTERS.viewMode).toBe('calendar');
    expect(DEFAULT_TIMELINE_FILTERS.accountIds).toEqual([]);
    expect(DEFAULT_TIMELINE_FILTERS.description).toBeNull();
  });

  it('currentMonthTimelineFilters preenche o mês corrente (1º ao último dia)', () => {
    vi.setSystemTime(new Date('2026-06-15T12:00:00'));
    const f = currentMonthTimelineFilters();
    expect(f.startDate).toBe('2026-06-01');
    expect(f.endDate).toBe('2026-06-30');
    vi.useRealTimers();
  });

  it('save + load faz round-trip mas nunca persiste description', () => {
    const f = { ...DEFAULT_TIMELINE_FILTERS, accountIds: ['a1'], description: 'aluguel' };
    saveTimelineFilters(f);
    const stored = JSON.parse(localStorage.getItem(TIMELINE_STORAGE_KEY)!);
    expect(stored.description).toBeUndefined();
    expect(loadTimelineFilters().accountIds).toEqual(['a1']);
    expect(loadTimelineFilters().description).toBeNull();
  });

  it('load sem storage cai no mês corrente', () => {
    vi.setSystemTime(new Date('2026-03-10T00:00:00'));
    const f = loadTimelineFilters();
    expect(f.startDate).toBe('2026-03-01');
    vi.useRealTimers();
  });
});
