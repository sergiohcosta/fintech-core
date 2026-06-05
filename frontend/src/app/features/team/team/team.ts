import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../core/services/auth';
import { InvitationService, InvitationSummary } from '../../../core/services/invitation';
import { Member, MembersService } from '../../../core/services/members';
import { InviteDialogComponent } from '../invite-dialog/invite-dialog';

@Component({
  selector: 'app-team',
  standalone: true,
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
  ],
  templateUrl: './team.html',
  styleUrl: './team.scss',
})
export class TeamComponent implements OnInit {
  private membersService = inject(MembersService);
  private invitationService = inject(InvitationService);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);
  private authService = inject(AuthService);

  members = signal<Member[]>([]);
  invitations = signal<InvitationSummary[]>([]);
  loading = signal(true);
  error = signal('');

  isAdmin = this.authService.isAdmin;

  readonly memberColumns = ['name', 'email', 'role'];
  readonly inviteColumns = ['email', 'status', 'expiresAt', 'actions'];

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading.set(true);
    this.error.set('');

    forkJoin({
      members: this.membersService.list(),
      invitations: this.invitationService.list(),
    }).subscribe({
      next: ({ members, invitations }) => {
        this.members.set(members);
        this.invitations.set(invitations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Erro ao carregar dados da equipe.');
        this.loading.set(false);
      },
    });
  }

  openInviteDialog(): void {
    const ref = this.dialog.open(InviteDialogComponent, { width: '480px' });
    ref.afterClosed().subscribe(() => this.loadAll());
  }

  revoke(id: string): void {
    this.invitationService.revoke(id).subscribe({
      next: () => this.loadAll(),
      error: (err) =>
        this.snackBar.open(err.error?.message ?? 'Erro ao revogar convite', 'Fechar', { duration: 4000 }),
    });
  }

  statusLabel(status: InvitationSummary['status']): string {
    return { PENDING: 'Pendente', ACCEPTED: 'Aceito', EXPIRED: 'Expirado' }[status];
  }

  statusColor(status: InvitationSummary['status']): string {
    return { PENDING: 'primary', ACCEPTED: 'accent', EXPIRED: '' }[status];
  }
}
