import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatBottomSheet, MatBottomSheetModule } from '@angular/material/bottom-sheet';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { TransactionQuickSheetComponent } from '../../../features/transaction/transaction-quick-sheet/transaction-quick-sheet';

// A barra inferior deixa os quatro destinos frequentes ao alcance do polegar;
// o drawer permanece reservado para navegação secundária sem competir no topo.
@Component({
  selector: 'app-bottom-tab-bar',
  imports: [RouterLink, RouterLinkActive, MatIconModule, MatButtonModule, MatBottomSheetModule],
  templateUrl: './bottom-tab-bar.html',
  styleUrl: './bottom-tab-bar.scss',
})
export class BottomTabBarComponent {
  private bottomSheet = inject(MatBottomSheet);

  openQuickEntry(): void {
    this.bottomSheet.open(TransactionQuickSheetComponent, { panelClass: 'quick-entry-sheet' });
  }
}
