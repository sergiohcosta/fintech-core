import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';


import { CreditCardService } from '../../../core/services/credit-card';
import { CreditCardModel } from '../../../core/models/credit-card';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';

import { Router, RouterLink } from '@angular/router';


@Component({
  selector: 'app-card-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    CurrencyPipe,
    RouterLink
  ],
  templateUrl: './card-list.html',
  styleUrl: './card-list.scss'
})
export class CardListComponent implements OnInit {
  private router = inject(Router);
  private service = inject(CreditCardService);
  cards = signal<CreditCardModel[]>([]);

  private dialog = inject(MatDialog);

  displayedColumns: string[] = ['color', 'name', 'brand', 'limit', 'closingDay', 'actions'];

  ngOnInit(): void {
    this.loadCards();
  }

  loadCards() {
    this.service.list().subscribe({
      next: (data) => this.cards.set(data),
      error: (err) => console.error('Erro:', err)
    });
  }

  onEdit(card: CreditCardModel) {
    this.router.navigate(['/credit-cards', card.id]);
  }

  onDelete(card: CreditCardModel) {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Cartão',
        message: `Tem certeza que deseja remover o cartão "${card.name}"?`,
        confirmText: 'Sim, excluir'
      }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result === true) {
        this.service.delete(card.id).subscribe({
          next: () => {
            this.loadCards();
          },
          error: (err) => console.error('Erro ao excluir', err)
        });
      }
    });
  }
}