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
  | 'amount'
  | 'transaction_date'
  | 'description'
  | 'direction'
  | 'payment_method';

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

/** Linha editável mínima usada pelo componente — só o que o payload de commit precisa. */
export interface CommitRow {
  stagedId: string;
  accountId: string | null;
  categoryId: string | null;
  status: string;
}

/**
 * Monta o payload de commit: só as linhas ainda PENDING e com conta escolhida. `categoryId`
 * vazio vira null (transação sem categoria, ajustável depois).
 */
export function buildCommitRequest(rows: CommitRow[]): ImportCommitRequestDTO {
  const items: StagedCommitItemDTO[] = rows
    .filter((r) => r.status === 'PENDING' && !!r.accountId)
    .map((r) => ({
      stagedId: r.stagedId,
      accountId: r.accountId as string,
      categoryId: r.categoryId ? r.categoryId : null,
    }));
  return { items };
}

/** Pré-condição do "Confirmar": existe ao menos uma linha PENDING e TODAS têm conta escolhida. */
export function allPendingHaveAccount(rows: CommitRow[]): boolean {
  const pending = rows.filter((r) => r.status === 'PENDING');
  return pending.length > 0 && pending.every((r) => !!r.accountId);
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
