import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { of, throwError } from 'rxjs';
import { InviteDialogComponent } from './invite-dialog';
import { InvitationService } from '../../../core/services/invitation';

describe('InviteDialogComponent', () => {
  let fixture: ComponentFixture<InviteDialogComponent>;
  let component: InviteDialogComponent;
  let invitationSvc: { create: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    invitationSvc = { create: vi.fn() };
    dialogRef = { close: vi.fn() };
    snackBar = { open: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [InviteDialogComponent],
      providers: [
        provideAnimationsAsync(),
        { provide: InvitationService, useValue: invitationSvc },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(InviteDialogComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('inicia no estado form', () => {
    expect(component.state()).toBe('form');
  });

  it('transiciona para success após criar convite', async () => {
    invitationSvc.create.mockReturnValue(of({
      token: 'tok',
      link: 'http://localhost:4200/accept-invite?token=tok',
      email: 'x@test.com',
      expiresAt: '2026-06-12T00:00:00',
    }));

    component.form.setValue({ email: 'x@test.com' });
    component.submit();
    await fixture.whenStable();

    expect(component.state()).toBe('success');
    expect(component.inviteLink()).toBe('http://localhost:4200/accept-invite?token=tok');
  });

  it('exibe snackbar e volta ao estado form em caso de erro', async () => {
    invitationSvc.create.mockReturnValue(
      throwError(() => ({ error: { message: 'Convite já pendente' } }))
    );

    component.form.setValue({ email: 'x@test.com' });
    component.submit();
    await fixture.whenStable();

    expect(component.state()).toBe('form');
    expect(snackBar.open).toHaveBeenCalledWith('Convite já pendente', 'Fechar', { duration: 4000 });
  });

  it('copyLink chama navigator.clipboard.writeText e sinaliza copiado', async () => {
    component.inviteLink.set('http://localhost:4200/accept-invite?token=tok');
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      writable: true,
      configurable: true,
    });

    component.copyLink();
    await fixture.whenStable();

    expect(writeText).toHaveBeenCalledWith('http://localhost:4200/accept-invite?token=tok');
    expect(component.copied()).toBe(true);
  });

  it('close chama dialogRef.close()', () => {
    component.close();
    expect(dialogRef.close).toHaveBeenCalled();
  });
});
