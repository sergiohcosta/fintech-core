// Lógica pura de parsing/formatação do formulário de transação (#148).
// Sem imports Angular → testável no Vitest sem TestBed (padrão do projeto).

/**
 * Interpreta um valor monetário digitado, aceitando formato pt-BR (`1.234,56`) e ponto-decimal
 * (`1234.56`). Retorna `null` quando não há número válido.
 *
 * Desambiguação (#148): antes, `.` era sempre removido como separador de milhar, então `1234.56`
 * (ponto decimal, comum ao digitar/colar) virava `123456` silenciosamente.
 * - Se há vírgula → vírgula é o decimal e pontos são milhares.
 * - Sem vírgula → `.` é decimal apenas se houver UM ponto seguido de 1–2 dígitos no fim
 *   (`1234.56`); caso contrário é milhar (`1.234` → 1234, comportamento pt-BR).
 */
export function parseAmountInput(raw: string): number | null {
  const s = raw.replace(/[^\d.,]/g, '');
  if (s === '') return null;

  let normalized: string;
  if (s.includes(',')) {
    normalized = s.replace(/\./g, '').replace(',', '.');
  } else {
    const isDecimalDot = /^\d+\.\d{1,2}$/.test(s);
    normalized = isDecimalDot ? s : s.replace(/\./g, '');
  }

  const value = parseFloat(normalized);
  return isNaN(value) ? null : value;
}

/**
 * Formata uma `Date` como `yyyy-MM-dd` usando os componentes LOCAIS (#148).
 * `toISOString()` converteria para UTC e, em fusos de offset positivo, a meia-noite local
 * retornaria o dia anterior (D−1).
 */
export function formatLocalDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
