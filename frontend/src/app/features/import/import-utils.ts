import type {
  CategoryResponseDTO,
  ImportCommitRequestDTO,
  StagedCommitItemDTO,
  StagedFieldValueDTO,
  StagedTransactionResponseDTO,
} from '../../core/api/fintechSaaSAPI.schemas';

/**
 * Lógica PURA da feature de importação — sem imports do Angular, testável no Vitest sem TestBed.
 * Formatação de confiança, régua do badge de baixa confiança e montagem do payload de commit.
 */

export type ReviewFieldKey =
  'amount' | 'transaction_date' | 'description' | 'direction' | 'payment_method';

/** Campos exibidos na revisão, na ordem. */
export const REVIEW_FIELD_KEYS: ReviewFieldKey[] = [
  'amount',
  'transaction_date',
  'description',
  'direction',
  'payment_method',
];

/**
 * Régua de "baixa confiança" para o badge. Alinhada ao `import.review.amount-threshold` do
 * backend (0.95) — o frontend só EXIBE; nunca decide requires_review (isso é do backend).
 */
export const LOW_CONFIDENCE_THRESHOLD = 0.95;

export function fieldOf(
  staged: StagedTransactionResponseDTO,
  key: string,
): StagedFieldValueDTO | undefined {
  return staged.fields?.[key];
}

export function fieldValue(staged: StagedTransactionResponseDTO, key: string): unknown {
  return fieldOf(staged, key)?.value ?? null;
}

export function fieldConfidence(staged: StagedTransactionResponseDTO, key: string): number | null {
  const c = fieldOf(staged, key)?.confidence;
  return c === undefined || c === null ? null : c;
}

/** Valor do campo como string, para exibir/editar (número, ISO de data ou texto). */
export function fieldValueAsString(staged: StagedTransactionResponseDTO, key: string): string {
  const v = fieldValue(staged, key);
  return v === null || v === undefined ? '' : String(v);
}

/** true quando a confiança está abaixo da régua (ou ausente) → destaca o campo no UI. */
export function isLowConfidence(
  confidence: number | null,
  threshold = LOW_CONFIDENCE_THRESHOLD,
): boolean {
  return confidence === null || confidence < threshold;
}

/** Confiança em porcentagem: 0.98 → "98%"; null → "—". */
export function formatConfidence(confidence: number | null): string {
  if (confidence === null) {
    return '—';
  }
  const clamped = Math.max(0, Math.min(1, confidence));
  return `${Math.round(clamped * 100)}%`;
}

/** Nível para a cor do badge (low = atenção, high = ok). */
export function confidenceLevel(
  confidence: number | null,
  threshold = LOW_CONFIDENCE_THRESHOLD,
): 'low' | 'high' {
  return isLowConfidence(confidence, threshold) ? 'low' : 'high';
}

/** Rótulo humano da direção (debit = saída/despesa, credit = entrada/receita). */
export function directionLabel(value: unknown): string {
  return value === 'credit' ? 'Entrada (receita)' : 'Saída (despesa)';
}

// --- Regionalização pt-BR de valor e data (exibição na revisão) ---

/** Valor cru (ponto decimal, ex. "1234.5") → "R$ 1.234,50"; inválido/vazio → "—". */
export function formatAmountCurrency(raw: string): string {
  const n = Number(raw);
  if (raw === '' || !Number.isFinite(n)) {
    return '—';
  }
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(n);
}

/** Valor cru (ponto decimal) → só os dígitos formatados pt-BR ("1.234,50"), sem símbolo — usado
 *  no input de edição (o "R$" vem de um `matTextPrefix` separado, como no formulário manual). */
export function formatAmountDisplay(raw: string): string {
  const n = Number(raw);
  if (raw === '' || !Number.isFinite(n)) {
    return '';
  }
  return new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(n);
}

/** Data ISO (yyyy-MM-dd) → "dd/mm/aaaa"; inválida/vazia → "—". `T00:00:00` evita o D-1 de fuso
 *  horário que `new Date('yyyy-MM-dd')` sozinho causaria em offsets positivos (mesmo cuidado de
 *  `formatLocalDate` em transaction-form.utils.ts). */
export function formatDatePtBr(raw: string): string {
  if (!raw) {
    return '—';
  }
  const d = new Date(`${raw}T00:00:00`);
  if (Number.isNaN(d.getTime())) {
    return raw;
  }
  return d.toLocaleDateString('pt-BR');
}

/** Linha editável mínima usada pelo componente — o que o payload de commit precisa MAIS os dois
 *  campos que o backend valida em `commit()` (amount/transaction_date) — precisamos deles aqui
 *  pra replicar a mesma validação ANTES de mandar a linha, nunca depois. */
export interface CommitRow {
  stagedId: string;
  accountId: string | null;
  categoryId: string | null;
  status: string;
  amount: string;
  transaction_date: string;
}

/** Valor "pronto pra lançar": presente e >= 0,01 — mesma régua de `ImportService#commit`
 *  (`toBigDecimal` + `compareTo(0.01)`) no backend, replicada aqui pra nunca depender do
 *  round-trip HTTP só pra descobrir que o valor está em branco/inválido. */
export function isAmountReady(raw: string): boolean {
  const n = Number(raw);
  return raw !== '' && Number.isFinite(n) && n >= 0.01;
}

/** Data "pronta pra lançar": presente e parseável. `T00:00:00` evita ambiguidade de fuso,
 *  mesmo cuidado de `formatDatePtBr`/`formatLocalDate`. */
export function isDateReady(raw: string): boolean {
  if (!raw) {
    return false;
  }
  return !Number.isNaN(new Date(`${raw}T00:00:00`).getTime());
}

/**
 * Linha realmente pronta pra ir no commit: PENDING, com conta escolhida E valor/data válidos.
 * Antes a checagem só olhava conta — uma linha com valor em branco passava pro backend, que
 * rejeitava com `BusinessException` (mensagem só com o UUID da staged, nada amigável) e, pior,
 * derrubava o commit inteiro junto (o método é `@Transactional` único: uma linha ruim invalida
 * as boas também). Validar aqui evita os dois problemas — a linha ruim simplesmente não entra
 * no lote, fica PENDING, e o usuário corrige com calma (mesma filosofia do gate relaxado).
 */
export function isReadyToCommit(row: CommitRow): boolean {
  return (
    row.status === 'PENDING' &&
    !!row.accountId &&
    isAmountReady(row.amount) &&
    isDateReady(row.transaction_date)
  );
}

/**
 * Monta o payload de commit: só as linhas realmente prontas (`isReadyToCommit`). `categoryId`
 * vazio vira null (transação sem categoria, ajustável depois).
 */
export function buildCommitRequest(rows: CommitRow[]): ImportCommitRequestDTO {
  const items: StagedCommitItemDTO[] = rows.filter(isReadyToCommit).map((r) => ({
    stagedId: r.stagedId,
    accountId: r.accountId as string,
    categoryId: r.categoryId ? r.categoryId : null,
  }));
  return { items };
}

/**
 * Pré-condição do "Confirmar": existe ao menos uma linha realmente pronta pra lançar (conta +
 * valor + data válidos). Antes (`allPendingHaveAccount`) exigia que TODAS as PENDING tivessem
 * conta; depois (`anyPendingHasAccount`) relaxou pra "só uma" — mas não olhava valor/data, o que
 * deixava passar pro commit uma linha que o backend ia rejeitar. `buildCommitRequest` já filtra
 * pela mesma régua, então as duas funções nunca divergem sobre o que é "pronto".
 */
export function anyRowReadyToCommit(rows: CommitRow[]): boolean {
  return rows.some(isReadyToCommit);
}

/**
 * Aplica `value` ao campo `field` das linhas cujo `stagedId` está em `selectedIds`, devolvendo
 * um array NOVO (sem mutar `rows` — padrão de `signal.set()`/`update()` do projeto). Linhas fora
 * do conjunto selecionado são preservadas por referência (evita re-render desnecessário de quem
 * compara por identidade). Genérico sobre `T` para servir tanto `CommitRow` quanto o `ReviewRow`
 * do componente (ambos têm `stagedId`), sem acoplar esta camada pura ao tipo do componente.
 */
export function applyBulkField<T extends { stagedId: string }, K extends keyof T>(
  rows: T[],
  selectedIds: readonly string[] | ReadonlySet<string>,
  field: K,
  value: T[K],
): T[] {
  const ids = selectedIds instanceof Set ? selectedIds : new Set(selectedIds);
  if (ids.size === 0) {
    return rows.slice();
  }
  return rows.map((row) => (ids.has(row.stagedId) ? { ...row, [field]: value } : row));
}

export interface CategoryOption {
  id: string;
  name: string;
}

// --- Dedup por arquivo (409/force — Onda 4/5 da Fase 2) ---

export interface DuplicateConflict {
  batchId: string;
  createdAt: string | null;
  filename: string | null;
}

interface HttpErrorLike {
  status?: number;
  error?: { batchId?: string; createdAt?: string | null; filename?: string | null };
}

/**
 * Reconhece um 409 de importação duplicada (corpo com `batchId`/`createdAt`/`filename`, gravado
 * pelo `DuplicateImportException` do backend) e devolve `null` para qualquer outro erro — o
 * componente decide o que fazer com cada caso, esta função só INTERPRETA a resposta HTTP.
 */
export function parseDuplicateConflict(error: unknown): DuplicateConflict | null {
  const err = error as HttpErrorLike;
  if (err?.status !== 409 || !err.error?.batchId) {
    return null;
  }
  return {
    batchId: err.error.batchId,
    createdAt: err.error.createdAt ?? null,
    filename: err.error.filename ?? null,
  };
}

/** Data do conflito em pt-BR; "data desconhecida" se ausente/ilegível — nunca quebra a UI. */
export function formatConflictDate(createdAt: string | null): string {
  if (!createdAt) {
    return 'data desconhecida';
  }
  const parsed = new Date(createdAt);
  if (Number.isNaN(parsed.getTime())) {
    return createdAt;
  }
  return parsed.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Mensagem exibida no card de conflito — data do batch anterior + nome do arquivo, se houver. */
export function conflictMessage(conflict: DuplicateConflict): string {
  const when = formatConflictDate(conflict.createdAt);
  const filenamePart = conflict.filename ? ` (${conflict.filename})` : '';
  return `Este arquivo já foi importado em ${when}${filenamePart}.`;
}

/** Achata a árvore de categorias em opções planas, com o caminho no nome (Pai › Filho). */
export function flattenCategories(
  tree: CategoryResponseDTO[] | undefined | null,
  prefix = '',
): CategoryOption[] {
  const out: CategoryOption[] = [];
  for (const c of tree ?? []) {
    const name = prefix ? `${prefix} › ${c.name}` : c.name;
    out.push({ id: c.id, name });
    if (c.children && c.children.length > 0) {
      out.push(...flattenCategories(c.children, name));
    }
  }
  return out;
}

// --- Agrupamento avulsa/parcelada (Onda 2 do Itau Parcelamento) ---

export interface InstallmentInfo {
  number: number;
  total: number;
}

/**
 * Lê installment_number/installment_total do fields — ausentes (Nubank/genérico) → null.
 * Importações geradas a partir de extratos do Itaú trazem o número e total da parcela;
 * outras fontes (Nubank, CSV genérico) deixam esses campos fora.
 */
export function installmentInfo(staged: StagedTransactionResponseDTO): InstallmentInfo | null {
  const number = fieldValue(staged, 'installment_number');
  const total = fieldValue(staged, 'installment_total');
  if (typeof number !== 'number' || typeof total !== 'number') {
    return null;
  }
  return { number, total };
}

/**
 * Aviso só pra parcela > 1 (a 1 já é anunciada pelo rótulo da seção "vai criar parcelamento").
 * Frontend exibe o texto num badge de atenção; null significa nenhum aviso a exibir.
 */
export function installmentBadgeText(info: InstallmentInfo): string | null {
  if (info.number === 1) {
    return null;
  }
  return `Parcela ${info.number} de ${info.total} — parcelas anteriores não importadas, confira antes de lançar`;
}

export interface RowGroup<T> {
  kind: 'avulsas' | 'parceladas';
  label: string;
  rows: T[];
}

/**
 * Particiona linhas em Avulsas/Parceladas preservando a ordem original dentro de cada grupo.
 * Grupo vazio não aparece na saída — a UI não precisa checar tamanho antes de renderizar.
 * Tipado de forma genérica sobre `T` para servir qualquer tipo de linha que tenha `installmentNumber`,
 * permitindo reutilização tanto na lista de staged quanto em outros contextos futuros.
 */
export function groupRowsByInstallment<T extends { installmentNumber: number | null }>(
  rows: T[],
): RowGroup<T>[] {
  const avulsas = rows.filter((r) => r.installmentNumber === null);
  const parceladas = rows.filter((r) => r.installmentNumber !== null);
  const groups: RowGroup<T>[] = [];
  if (avulsas.length > 0) {
    groups.push({ kind: 'avulsas', label: 'Avulsas', rows: avulsas });
  }
  if (parceladas.length > 0) {
    groups.push({ kind: 'parceladas', label: 'Parceladas', rows: parceladas });
  }
  return groups;
}
