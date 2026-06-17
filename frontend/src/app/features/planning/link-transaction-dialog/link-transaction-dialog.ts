import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { BudgetItemResponse, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface LinkTransactionDialogData {
  item: BudgetItemResponse;
  cycleId: string;
  mode?: 'link' | 'realize';
}

@Component({
  selector: 'app-link-transaction-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatDialogModule, MatIconModule, MatTableModule,
  ],
  templateUrl: './link-transaction-dialog.html',
})
export class LinkTransactionDialogComponent implements OnInit {
  private readonly txService = inject(TransactionsService);
  private readonly dialogRef = inject(MatDialogRef<LinkTransactionDialogComponent>);
  readonly data: LinkTransactionDialogData = inject(MAT_DIALOG_DATA);

  readonly transactions = signal<TransactionResponseDTO[]>([]);
  readonly loading = signal(true);
  readonly isRealizeMode = () => this.data.mode === 'realize';
  readonly isInstallment = () => this.data.item.source === 'INSTALLMENT';

  displayedColumns = ['date', 'description', 'amount', 'select'];

  ngOnInit(): void {
    const item = this.data.item;
    this.txService.listTransactions({ type: item.type })
      .subscribe({
        next: (result: TransactionResponseDTO[]) => {
          const filtered = this.isInstallment() && item.installmentGroupId
            ? result.filter(tx => tx.installmentGroupId === item.installmentGroupId)
            : result;
          this.transactions.set(filtered);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  select(tx: TransactionResponseDTO): void {
    this.dialogRef.close(tx.id);
  }

  realizeWithoutLink(): void {
    this.dialogRef.close(null);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
