import { TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';
import { effectiveSortDate } from '../timeline-shared';

/**
 * Converte data ISO (YYYY-MM-DD) em dias desde epoch (meia-noite UTC).
 * Usado para cálculos de posição linear na timeline.
 */
function toEpochDay(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number);
  return Math.round(Date.UTC(y, m - 1, d) / 86_400_000);
}

/**
 * Calcula a posição horizontal (em pixels) de uma data dentro de um range.
 * Mapeamento linear: dataInício=0px, dataFim=containerWidth.
 * Range degenerado (início==fim) retorna o centro.
 *
 * @param date Data da transação (YYYY-MM-DD)
 * @param rangeStart Início do range (YYYY-MM-DD)
 * @param rangeEnd Fim do range (YYYY-MM-DD)
 * @param containerWidth Largura do container em pixels
 * @returns Posição em pixels desde o início (0 até containerWidth)
 */
export function calculateMarkerPosition(
  date: string, rangeStart: string, rangeEnd: string, containerWidth: number,
): number {
  const start = toEpochDay(rangeStart);
  const end = toEpochDay(rangeEnd);
  const span = end - start;
  if (span <= 0) return containerWidth / 2; // range degenerado → centro
  const offset = toEpochDay(date) - start;
  return (offset / span) * containerWidth;
}

/**
 * Representa um marcador na timeline com múltiplas transações agrupadas por dia.
 */
export interface PositionedMarker {
  /** Data efetiva da transação (ISO YYYY-MM-DD ou data de fatura) */
  date: string;
  /** Posição horizontal do marcador em pixels */
  x: number;
  /** Índice sequencial dentro do grupo (0-based), para stacking visual */
  stackIndex: number;
  /** Transações agrupadas neste marcador */
  transactions: TransactionResponseDTO[];
}

/**
 * Resolve colisões: agrupa transações do mesmo dia-efetivo num único marcador empilhado.
 * Transações com invoice usam `invoice.dueDate`; demais usam `transaction.date`.
 * Marcadores são ordenados cronologicamente; stackIndex é sequencial.
 *
 * @param txs Array de transações
 * @param rangeStart Início do range visual (YYYY-MM-DD)
 * @param rangeEnd Fim do range visual (YYYY-MM-DD)
 * @param containerWidth Largura do container em pixels
 * @returns Array de marcadores posicionados, ordenados por data
 */
export function resolveCollisions(
  txs: TransactionResponseDTO[], rangeStart: string, rangeEnd: string, containerWidth: number,
): PositionedMarker[] {
  // Colapsa transações do mesmo dia-efetivo num único marcador (a "colisão" é por dia).
  const byDay = new Map<string, TransactionResponseDTO[]>();
  for (const t of txs) {
    const key = effectiveSortDate(t);
    (byDay.get(key) ?? byDay.set(key, []).get(key)!).push(t);
  }
  return [...byDay.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, transactions], i) => ({
      date,
      x: calculateMarkerPosition(date, rangeStart, rangeEnd, containerWidth),
      stackIndex: i,
      transactions,
    }));
}
