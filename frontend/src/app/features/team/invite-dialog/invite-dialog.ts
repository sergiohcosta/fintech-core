import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { InvitationService } from '../../../core/services/invitation';

type DialogState = 'form' | 'submitting' | 'success';

@Component({
  selector: 'app-invite-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './invite-dialog.html',
})
export class InviteDialogComponent {
  private dialogRef = inject(MatDialogRef<InviteDialogComponent>);
  private fb = inject(FormBuilder);
  private invitationService = inject(InvitationService);
  private snackBar = inject(MatSnackBar);

  state = signal<DialogState>('form');
  inviteLink = signal('');
  copied = signal(false);

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  submit(): void {
    if (this.form.invalid) return;
    this.state.set('submitting');

    this.invitationService.create({ email: this.form.value.email! }).subscribe({
      next: (response) => {
        this.inviteLink.set(response.link);
        this.state.set('success');
      },
      error: (err) => {
        this.snackBar.open(err.error?.message ?? 'Erro ao criar convite', 'Fechar', { duration: 4000 });
        this.state.set('form');
      },
    });
  }

  copyLink(): void {
    navigator.clipboard.writeText(this.inviteLink()).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}
