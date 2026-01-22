import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';

import { CreditCardService } from '../../../core/services/credit-card';
import { CreditCardModel } from '../../../core/models/credit-card';

@Component({
  selector: 'app-card-list',
  standalone: true,
  imports: [
    CommonModule, 
    MatTableModule, 
    MatButtonModule, 
    MatIconModule,
    MatCardModule,
    CurrencyPipe
  ],
  // AQUI ESTÁ A SIMPLIFICAÇÃO:
  templateUrl: './card-list.html',
  styleUrl: './card-list.scss'
})
export class CardListComponent implements OnInit {
  
  private service = inject(CreditCardService);
  cards = signal<CreditCardModel[]>([]);
  
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
    console.log('Editar', card);
  }

  onDelete(card: CreditCardModel) {
    if(confirm(`Excluir ${card.name}?`)) {
       this.service.delete(card.id).subscribe(() => this.loadCards());
    }
  }
}