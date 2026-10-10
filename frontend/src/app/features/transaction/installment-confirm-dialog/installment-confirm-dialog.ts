import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { InstallmentPreviewDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { formatInstallmentRows } from './installment-confirm-dialog.utils';

@Component({
  selector: 'app-installment-confirm-dialog',
  standalone: true,
  imports: [MatButtonModule, MatDialogModule, MatIconModule],
  templateUrl: './installment-confirm-dialog.html',
  styleUrl: './installment-confirm-dialog.scss',
})
export class InstallmentConfirmDialog {
  private readonly dialogRef = inject(MatDialogRef<InstallmentConfirmDialog, boolean>);
  private readonly installments = signal(inject<InstallmentPreviewDTO[]>(MAT_DIALOG_DATA));

  readonly rows = computed(() => formatInstallmentRows(this.installments()));
  readonly skippedCount = computed(() => this.rows().filter((row) => !row.willCreate).length);
  readonly allSkipped = computed(
    () => this.rows().length > 0 && this.skippedCount() === this.rows().length,
  );

  cancel(): void {
    this.dialogRef.close(false);
  }

  confirm(): void {
    if (this.allSkipped()) return;
    this.dialogRef.close(true);
  }
}
