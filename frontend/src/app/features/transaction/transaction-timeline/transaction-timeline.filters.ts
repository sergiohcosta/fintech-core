import { TransactionType, TransactionStatus } from '../transaction-list/transaction-filters/transaction-filters.types';

export type TimelineViewMode = 'calendar' | 'grouped' | 'horizontal';

export interface TimelineFilters {
  accountIds: string[];
  statuses: TransactionStatus[];
  types: TransactionType[];
  startDate: string | null;
  endDate: string | null;
  description: string | null;
  viewMode: TimelineViewMode;
}

export const TIMELINE_STORAGE_KEY = 'fintech.timeline.filters';

export const DEFAULT_TIMELINE_FILTERS: TimelineFilters = {
  accountIds: [],
  statuses: [],
  types: [],
  startDate: null,
  endDate: null,
  description: null,
  viewMode: 'calendar',
};

const pad = (n: number) => String(n).padStart(2, '0');

export function currentMonthTimelineFilters(): TimelineFilters {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  const lastDay = new Date(year, month, 0).getDate();
  return {
    ...DEFAULT_TIMELINE_FILTERS,
    startDate: `${year}-${pad(month)}-01`,
    endDate: `${year}-${pad(month)}-${pad(lastDay)}`,
  };
}

export function loadTimelineFilters(): TimelineFilters {
  try {
    const raw = localStorage.getItem(TIMELINE_STORAGE_KEY);
    if (!raw) return currentMonthTimelineFilters();
    const parsed = JSON.parse(raw) as Partial<TimelineFilters>;
    // description nunca é persistida — sempre reinicia limpa
    return { ...currentMonthTimelineFilters(), ...parsed, description: null };
  } catch {
    return currentMonthTimelineFilters();
  }
}

export function saveTimelineFilters(f: TimelineFilters): void {
  try {
    const { description: _omit, ...toSave } = f; // descrição é efêmera
    localStorage.setItem(TIMELINE_STORAGE_KEY, JSON.stringify(toSave));
  } catch {
    // localStorage cheio ou bloqueado — ignorar silenciosamente
  }
}
