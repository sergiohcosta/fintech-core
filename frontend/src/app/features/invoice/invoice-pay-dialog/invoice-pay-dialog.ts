import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse, AccountType, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { formatLocalDate } from '../../transaction/transaction-form/transaction-form.utils';

export interface InvoicePayDialogResult {
  sourceAccountId: string;
  paymentDate: string;
}

interface DialogData {
  invoice: InvoiceResponseDTO;
}

@Component({
  selector: 'app-invoice-pay-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, FormsModule,
    MatDialogModule, MatSelectModule, MatFormFieldModule, MatButtonModule,
    MatDatepickerModule, MatInputModule
  ],
  templateUrl: './invoice-pay-dialog.html'
})
export class InvoicePayDialogComponent implements OnInit {
  private dialogRef = inject(MatDialogRef<InvoicePayDialogComponent>);
  data = inject<DialogData>(MAT_DIALOG_DATA);
  private accountsService = inject(AccountsService);

  accounts = signal<AccountResponse[]>([]);
  selectedAccountId = signal<string | null>(null);
  // Sugestão = hoje ao abrir (#199); max do datepicker espelha a validação do backend
  // (rejeita data futura) para não deixar a UI oferecer o que o servidor recusa.
  paymentDate = signal<Date | null>(new Date());
  maxDate = new Date();

  // AccountType é um const object (não enum TypeScript), então AccountType.CREDIT_CARD === 'CREDIT_CARD'.
  // A comparação funciona normalmente com o tipo string union gerado pelo Orval.
  eligibleAccounts = computed(() =>
    this.accounts().filter(a => a.type !== AccountType.CREDIT_CARD && a.active)
  );

  hasNoEligibleAccounts = computed(() => this.eligibleAccounts().length === 0);

  get invoice(): InvoiceResponseDTO { return this.data.invoice; }

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe({
      next: (data) => this.accounts.set(data)
    });
  }

  confirm(): void {
    const id = this.selectedAccountId();
    const date = this.paymentDate();
    if (!id || !date) return;
    this.dialogRef.close({ sourceAccountId: id, paymentDate: formatLocalDate(date) } as InvoicePayDialogResult);
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
