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

  displayedColumns = ['date', 'description', 'amount', 'select'];

  ngOnInit(): void {
    const itemType = this.data.item.type;
    this.txService.listTransactions({ type: itemType })
      .subscribe({
        next: (result: TransactionResponseDTO[]) => {
          this.transactions.set(result);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  select(tx: TransactionResponseDTO): void {
    this.dialogRef.close(tx.id);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
