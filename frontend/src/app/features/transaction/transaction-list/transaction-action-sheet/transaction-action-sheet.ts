import { Component, inject, signal } from '@angular/core';
import { MAT_BOTTOM_SHEET_DATA, MatBottomSheetModule, MatBottomSheetRef } from '@angular/material/bottom-sheet';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import { FormsModule } from '@angular/forms';
import { DeleteInstallmentScope } from '../../../../core/api/fintechSaaSAPI.schemas';

export interface TransactionActionSheetData {
  title: string;
  message: string;
  confirmText: string;
  installment?: boolean;
}

export type TransactionActionSheetResult = boolean | { scope: DeleteInstallmentScope };

// A confirmação fica no alcance do polegar no mobile e fecha antes de qualquer exclusão,
// evitando que um swipe acidental apague dados imediatamente.
@Component({
  selector: 'app-transaction-action-sheet',
  standalone: true,
  imports: [MatBottomSheetModule, MatButtonModule, MatIconModule, MatRadioModule, FormsModule],
  templateUrl: './transaction-action-sheet.html',
  styleUrl: './transaction-action-sheet.scss',
})
export class TransactionActionSheetComponent {
  readonly data = inject<TransactionActionSheetData>(MAT_BOTTOM_SHEET_DATA);
  private readonly sheetRef = inject<MatBottomSheetRef<TransactionActionSheetComponent, TransactionActionSheetResult>>(MatBottomSheetRef);
  readonly scope = signal<DeleteInstallmentScope>('SINGLE');

  cancel(): void {
    this.sheetRef.dismiss(false);
  }

  confirm(): void {
    this.sheetRef.dismiss(this.data.installment ? { scope: this.scope() } : true);
  }
}
