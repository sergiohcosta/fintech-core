import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { LoginComponent } from './login';

describe('LoginComponent', () => {
  let component: LoginComponent;
  let fixture: ComponentFixture<LoginComponent>;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter([]), provideHttpClient()],
    })
    .compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('isInvalid signal reflete a validade do formulário reativamente', async () => {
    component.form.patchValue({ email: '', password: '' });
    await fixture.whenStable();
    expect(component.isInvalid()).toBe(true);

    component.form.patchValue({ email: 'user@fintech.com', password: 'Password123' });
    await fixture.whenStable();
    expect(component.isInvalid()).toBe(false);

    component.form.patchValue({ email: 'invalido' });
    await fixture.whenStable();
    expect(component.isInvalid()).toBe(true);
  });
});
