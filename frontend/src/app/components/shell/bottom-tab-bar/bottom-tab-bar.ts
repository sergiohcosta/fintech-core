import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

// A barra inferior deixa os quatro destinos frequentes ao alcance do polegar;
// o drawer permanece reservado para navegação secundária sem competir no topo.
@Component({
  selector: 'app-bottom-tab-bar',
  imports: [RouterLink, RouterLinkActive, MatIconModule, MatButtonModule, MatSnackBarModule],
  templateUrl: './bottom-tab-bar.html',
  styleUrl: './bottom-tab-bar.scss',
})
export class BottomTabBarComponent {
  private snackBar = inject(MatSnackBar);

  showQuickEntryNotice(): void {
    this.snackBar.open('Lançamento rápido virá na próxima fase', 'OK', { duration: 3000 });
  }
}
