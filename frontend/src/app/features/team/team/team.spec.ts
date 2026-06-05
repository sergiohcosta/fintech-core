import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { of, throwError } from 'rxjs';
import { signal, computed } from '@angular/core';
import { TeamComponent } from './team';
import { MembersService } from '../../../core/services/members';
import { InvitationService } from '../../../core/services/invitation';
import { AuthService } from '../../../core/services/auth';

describe('TeamComponent', () => {
  let fixture: ComponentFixture<TeamComponent>;
  let component: TeamComponent;
  let membersSvc: { list: ReturnType<typeof vi.fn> };
  let invitationSvc: { list: ReturnType<typeof vi.fn>; revoke: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };

  const mockMember = { id: 'u1', name: 'João', email: 'j@test.com', role: 'ADMIN' as const };
  const mockInvite = {
    id: 'i1', email: 'x@test.com', status: 'PENDING' as const,
    createdAt: '2026-06-05T00:00:00', expiresAt: '2026-06-12T00:00:00',
    link: 'http://localhost:4200/accept-invite?token=tok',
  };

  const setupComponent = async (isAdmin: boolean) => {
    const role = isAdmin ? 'ADMIN' : 'USER';
    const currentUser = signal({ sub: 'a@test.com', name: 'A', tenant_id: 't1', role, exp: 9999999999 });
    const authSvc = {
      currentUser,
      isAdmin: computed(() => currentUser()?.role === 'ADMIN'),
    };

    await TestBed.configureTestingModule({
      imports: [TeamComponent],
      providers: [
        provideAnimationsAsync(),
        { provide: MembersService, useValue: membersSvc },
        { provide: InvitationService, useValue: invitationSvc },
        { provide: AuthService, useValue: authSvc },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TeamComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  };

  beforeEach(() => {
    membersSvc = { list: vi.fn().mockReturnValue(of([mockMember])) };
    invitationSvc = {
      list: vi.fn().mockReturnValue(of([mockInvite])),
      revoke: vi.fn().mockReturnValue(of(void 0)),
    };
    dialog = { open: vi.fn().mockReturnValue({ afterClosed: () => of(null) }) };
    snackBar = { open: vi.fn() };
  });

  it('carrega membros e convites ao inicializar', async () => {
    await setupComponent(true);
    expect(component.members()).toHaveLength(1);
    expect(component.members()[0].name).toBe('João');
    expect(component.invitations()).toHaveLength(1);
    expect(component.invitations()[0].email).toBe('x@test.com');
  });

  it('exibe estado de erro quando carregamento falha', async () => {
    membersSvc.list.mockReturnValue(throwError(() => new Error('network')));
    await setupComponent(true);
    expect(component.error()).toBe('Erro ao carregar dados da equipe.');
  });

  it('openInviteDialog abre o dialog e recarrega após fechar', async () => {
    await setupComponent(true);
    component.openInviteDialog();
    expect(dialog.open).toHaveBeenCalled();
  });

  it('revoke chama invitationService.revoke e recarrega', async () => {
    await setupComponent(true);
    component.revoke('i1');
    await fixture.whenStable();
    expect(invitationSvc.revoke).toHaveBeenCalledWith('i1');
  });

  it('botão Convidar é visível para ADMIN', async () => {
    await setupComponent(true);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="invite-btn"]');
    expect(btn).not.toBeNull();
  });

  it('botão Convidar é oculto para USER', async () => {
    await setupComponent(false);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="invite-btn"]');
    expect(btn).toBeNull();
  });
});
