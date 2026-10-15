import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleChange, MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AuthService } from '../../core/services/auth';
import { BiometricAuthService } from '../../core/services/biometric-auth.service';
import { TokenStorageService } from '../../core/services/token-storage.service';

// O perfil usa o email do token para registrar a credencial no gerenciador do navegador;
// a senha continua sendo o JWT e nunca é enviada de volta ao backend.
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule],
  templateUrl: './profile.html',
  styleUrl: './profile.scss',
})
export class ProfileComponent {
  readonly auth = inject(AuthService);
  readonly biometricAuth = inject(BiometricAuthService);
  readonly tokenStorage = inject(TokenStorageService);
  private readonly snackBar = inject(MatSnackBar);
  readonly saving = signal(false);

  async onBiometricToggle(event: MatSlideToggleChange): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    try {
      if (event.checked) {
        const token = this.auth.getToken();
        const userEmail = this.auth.currentUser()?.sub;
        if (!token || !userEmail) throw new Error('Entre novamente para ativar a biometria.');
        await this.biometricAuth.enableForCurrentUser(token, userEmail);
        this.snackBar.open('Biometria ativada neste dispositivo.', 'OK', { duration: 3000 });
      } else {
        await this.biometricAuth.disableBiometric();
        this.snackBar.open('Biometria desativada.', 'OK', { duration: 3000 });
      }
    } catch (error: unknown) {
      this.snackBar.open(
        error instanceof Error ? error.message : 'Não foi possível alterar a biometria.',
        'OK',
        { duration: 5000 },
      );
    } finally {
      this.saving.set(false);
    }
  }
}
