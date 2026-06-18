import { Component, inject } from '@angular/core';
import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';

import { BudgetItemResponse, TransactionResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface LinkBudgetItemDialogData {
  transaction: TransactionResponseDTO;
  pendingItems: BudgetItemResponse[];
}

@Component({
  selector: 'app-link-budget-item-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, DatePipe,
    MatButtonModule, MatDialogModule, MatIconModule, MatTableModule,
  ],
  templateUrl: './link-budget-item-dialog.html',
})
export class LinkBudgetItemDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<LinkBudgetItemDialogComponent>);
  readonly data: LinkBudgetItemDialogData = inject(MAT_DIALOG_DATA);

  readonly displayedColumns = ['description', 'expectedDate', 'amount', 'select'];

  select(item: BudgetItemResponse): void {
    this.dialogRef.close(item.id);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
