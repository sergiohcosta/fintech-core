import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';

@Component({
  selector: 'app-account-list',
  standalone: true,
  imports: [
    CommonModule, CurrencyPipe, RouterLink,
    MatTableModule, MatButtonModule, MatIconModule,
    MatTooltipModule, MatSnackBarModule, MatDialogModule
  ],
  templateUrl: './account-list.html',
  styleUrl: './account-list.scss'
})
export class AccountList implements OnInit {
  private service = inject(AccountsService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  accounts = signal<AccountResponse[]>([]);
  displayedColumns = ['name', 'type', 'balance', 'flags', 'actions'];

  ngOnInit() { this.load(); }

  load() {
    this.service.listAccounts().subscribe({
      next: (data) => this.accounts.set(data),
      error: () => this.snackBar.open('Erro ao carregar contas.', 'Fechar', { duration: 5000 })
    });
  }

  archive(id: string) {
    const ref = this.dialog.open(ConfirmationDialogComponent, {
      data: { message: 'Deseja arquivar esta conta?' }
    });
    ref.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;
      this.service.deleteAccount(id).subscribe({
        next: () => { this.snackBar.open('Conta arquivada.', 'OK', { duration: 3000 }); this.load(); },
        error: () => this.snackBar.open('Erro ao arquivar conta.', 'Fechar', { duration: 5000 })
      });
    });
  }

  typeLabel(type: string): string {
    const labels: Record<string, string> = {
      CHECKING: 'Conta Corrente',
      INVESTMENT: 'Investimento',
      CREDIT_CARD: 'Cartão de Crédito',
      CASH: 'Carteira'
    };
    return labels[type] ?? type;
  }
}
