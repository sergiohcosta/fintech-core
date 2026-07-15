import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { effectiveSortDate, dayTotals, DayTotals } from '../timeline-shared';

export interface DayCell {
  date: string | null;
  dayOfMonth: number | null;
  inMonth: boolean;
  transactions: TransactionResponseDTO[];
  totals: DayTotals;
}

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (y: number, m: number, d: number) => `${y}-${pad(m)}-${pad(d)}`;

export function buildMonthGrid(monthAnchor: string, txs: TransactionResponseDTO[]): DayCell[] {
  const [year, month] = monthAnchor.split('-').map(Number);
  // Agrupa transações por dia-efetivo uma única vez (evita varrer a lista por célula)
  const byDay = new Map<string, TransactionResponseDTO[]>();
  for (const t of txs) {
    const key = effectiveSortDate(t);
    (byDay.get(key) ?? byDay.set(key, []).get(key)!).push(t);
  }

  const firstWeekday = new Date(year, month - 1, 1).getDay(); // 0=domingo
  const daysInMonth = new Date(year, month, 0).getDate();

  const cells: DayCell[] = [];
  const emptyCell = (): DayCell => ({
    date: null, dayOfMonth: null, inMonth: false, transactions: [], totals: { income: 0, expense: 0, net: 0 },
  });

  // Padding antes do dia 1 (semana começa no domingo)
  for (let i = 0; i < firstWeekday; i++) cells.push(emptyCell());

  for (let d = 1; d <= daysInMonth; d++) {
    const date = iso(year, month, d);
    const transactions = byDay.get(date) ?? [];
    cells.push({ date, dayOfMonth: d, inMonth: true, transactions, totals: dayTotals(transactions) });
  }

  // Completa até 42 células (6 linhas fixas — evita o grid "pular" de altura ao trocar de mês)
  while (cells.length < 42) cells.push(emptyCell());
  return cells;
}

export function formatMonthLabel(monthAnchor: string): string {
  const [year, month] = monthAnchor.split('-').map(Number);
  const label = new Date(year, month - 1, 1).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
  // toLocaleDateString devolve "junho de 2026" → capitaliza a inicial
  return label.charAt(0).toUpperCase() + label.slice(1);
}
