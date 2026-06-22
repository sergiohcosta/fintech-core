import { Component, inject } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { CardBreakdown } from './budget-cycle-breakdown';

// Diálogo puramente apresentacional: recebe a composição já calculada (buildBreakdown)
// e a renderiza. Um único diálogo serve aos quatro cards.
@Component({
  selector: 'app-breakdown-dialog',
  standalone: true,
  imports: [CommonModule, CurrencyPipe, MatButtonModule, MatDialogModule],
  templateUrl: './breakdown-dialog.html',
  styleUrl: './breakdown-dialog.scss',
})
export class BreakdownDialogComponent {
  readonly data = inject<CardBreakdown>(MAT_DIALOG_DATA);
}
