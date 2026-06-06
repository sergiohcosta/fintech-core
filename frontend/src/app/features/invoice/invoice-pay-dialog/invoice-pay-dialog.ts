import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatButtonModule } from '@angular/material/button';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse, AccountType, InvoiceResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface InvoicePayDialogResult {
  sourceAccountId: string;
}

interface DialogData {
  invoice: InvoiceResponseDTO;
}

@Component({
  selector: 'app-invoice-pay-dialog',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, FormsModule,
    MatDialogModule, MatSelectModule, MatFormFieldModule, MatButtonModule
  ],
  templateUrl: './invoice-pay-dialog.html'
})
export class InvoicePayDialogComponent implements OnInit {
  private dialogRef = inject(MatDialogRef<InvoicePayDialogComponent>);
  data = inject<DialogData>(MAT_DIALOG_DATA);
  private accountsService = inject(AccountsService);

  accounts = signal<AccountResponse[]>([]);
  selectedAccountId = signal<string | null>(null);

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
    if (!id) return;
    this.dialogRef.close({ sourceAccountId: id } as InvoicePayDialogResult);
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }
}
