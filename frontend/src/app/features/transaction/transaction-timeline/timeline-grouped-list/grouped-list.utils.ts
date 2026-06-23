import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { effectiveSortDate, dayTotals } from '../timeline-shared';

export type RelativeBucket = 'today' | 'yesterday' | 'thisWeek' | 'lastWeek' | 'thisMonth' | 'older';

export interface RelativeGroup {
  bucket: RelativeBucket;
  label: string;
  transactions: TransactionResponseDTO[];
  income: number;
  expense: number;
  net: number;
}

// Ordem de exibição (recente → antigo) e rótulos pt-BR.
const BUCKET_ORDER: { bucket: RelativeBucket; label: string }[] = [
  { bucket: 'today', label: 'Hoje' },
  { bucket: 'yesterday', label: 'Ontem' },
  { bucket: 'thisWeek', label: 'Esta semana' },
  { bucket: 'lastWeek', label: 'Semana passada' },
  { bucket: 'thisMonth', label: 'Este mês' },
  { bucket: 'older', label: 'Mais antigos' },
];

// Diferença em dias inteiros entre duas datas ISO (a - b), via UTC para evitar DST.
function daysBetween(a: string, b: string): number {
  const [ay, am, ad] = a.split('-').map(Number);
  const [by, bm, bd] = b.split('-').map(Number);
  const ms = Date.UTC(ay, am - 1, ad) - Date.UTC(by, bm - 1, bd);
  return Math.round(ms / 86_400_000);
}

function classify(date: string, today: string): RelativeBucket {
  const diff = daysBetween(today, date); // hoje - data; positivo = passado
  if (diff === 0) return 'today';
  if (diff === 1) return 'yesterday';
  // thisWeek: diff 2..6 (9..13 de junho quando hoje é 15)
  if (diff >= 2 && diff <= 6) return 'thisWeek';
  // lastWeek: diff 7..13 (2..8 de junho quando hoje é 15)
  if (diff >= 7 && diff <= 13) return 'lastWeek';
  // thisMonth: mesmo mês-calendário (junho) que não caiu em yesterday, thisWeek ou lastWeek
  if (date.slice(0, 7) === today.slice(0, 7)) return 'thisMonth';
  return 'older';
}

export function groupByRelativePeriod(txs: TransactionResponseDTO[], today: string): RelativeGroup[] {
  const buckets = new Map<RelativeBucket, TransactionResponseDTO[]>();
  for (const t of txs) {
    const b = classify(effectiveSortDate(t), today);
    (buckets.get(b) ?? buckets.set(b, []).get(b)!).push(t);
  }

  const result: RelativeGroup[] = [];
  for (const { bucket, label } of BUCKET_ORDER) {
    const items = buckets.get(bucket);
    if (!items || items.length === 0) continue;
    items.sort((a, b) => effectiveSortDate(b).localeCompare(effectiveSortDate(a)));
    const totals = dayTotals(items);
    result.push({ bucket, label, transactions: items, income: totals.income, expense: totals.expense, net: totals.net });
  }
  return result;
}
