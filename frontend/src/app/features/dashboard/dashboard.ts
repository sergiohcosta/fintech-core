import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { AuthService } from '../../core/services/auth';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatCardModule],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})

export class DashboardComponent {

  private authService = inject(AuthService);

  user = this.authService.currentUser;

  logout() {
    this.authService.logout();
  }

}
