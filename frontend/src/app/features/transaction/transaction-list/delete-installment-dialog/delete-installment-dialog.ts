import { Component, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { DeleteInstallmentScope, TransactionResponseDTO } from '../../../../core/api/fintechSaaSAPI.schemas';

export interface DeleteInstallmentDialogResult {
  scope: DeleteInstallmentScope;
}

interface DialogData {
  transaction: TransactionResponseDTO;
}

@Component({
  selector: 'app-delete-installment-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatRadioModule, MatButtonModule, MatIconModule, FormsModule],
  templateUrl: './delete-installment-dialog.html',
  styleUrl: './delete-installment-dialog.scss'
})
export class DeleteInstallmentDialogComponent {
  private dialogRef = inject(MatDialogRef<DeleteInstallmentDialogComponent>);
  data: DialogData = inject(MAT_DIALOG_DATA);

  scope = signal<DeleteInstallmentScope>('SINGLE');

  get transaction() { return this.data.transaction; }

  showPaidWarning = computed(() => this.scope() !== 'SINGLE');

  confirm(): void {
    this.dialogRef.close({ scope: this.scope() } as DeleteInstallmentDialogResult);
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
