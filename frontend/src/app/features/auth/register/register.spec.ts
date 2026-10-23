import { describe, it, expect, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';

import { RegisterComponent } from './register';

describe('RegisterComponent', () => {
  let component: RegisterComponent;
  let fixture: ComponentFixture<RegisterComponent>;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [provideRouter([]), provideHttpClient()],
    })
    .compileComponents();

    fixture = TestBed.createComponent(RegisterComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('isInvalid signal reflete a validade do formulário reativamente', async () => {
    expect(component.isInvalid()).toBe(true);

    component.form.patchValue({
      name: 'Família Costa',
      adminName: 'Sergio',
      adminEmail: 'sergio@fintech.com',
      password: 'StrongPassword123'
    });
    await fixture.whenStable();
    expect(component.isInvalid()).toBe(false);

    component.form.patchValue({ adminEmail: 'email-invalido' });
    await fixture.whenStable();
    expect(component.isInvalid()).toBe(true);
  });
});
