export type TransactionType = 'INCOME' | 'EXPENSE' | 'TRANSFER';
export type TransactionStatus = 'PENDING' | 'PAID' | 'CANCELLED';

export interface TransactionResponse {
  id: string;
  description: string;
  amount: number;
  date: string;
  type: TransactionType;
  status: TransactionStatus;
  installmentLabel: string | null;
  categoryName: string | null;
  creditCardName: string | null;
}

export interface TransactionRequest {
  description: string;
  amount: number;
  date: string;
  type: TransactionType;
  status?: TransactionStatus;
  totalInstallments?: number;
  categoryId?: string;
  creditCardId?: string;
}
