import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { describe, it, expect, beforeEach } from 'vitest';

import { WelcomeWizardComponent } from './welcome-wizard';

describe('WelcomeWizardComponent', () => {
  let component: WelcomeWizardComponent;
  let fixture: ComponentFixture<WelcomeWizardComponent>;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [WelcomeWizardComponent],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(WelcomeWizardComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('inicia na etapa 1 e permite avançar até a etapa 3', () => {
    expect(component.currentStep()).toBe(1);

    component.nextStep();
    expect(component.currentStep()).toBe(2);

    component.nextStep();
    expect(component.currentStep()).toBe(3);

    // Não deve ultrapassar o total de etapas (3)
    component.nextStep();
    expect(component.currentStep()).toBe(3);
  });

  it('permite retroceder etapas até o piso 1', () => {
    component.currentStep.set(3);

    component.prevStep();
    expect(component.currentStep()).toBe(2);

    component.prevStep();
    expect(component.currentStep()).toBe(1);

    // Não deve diminuir abaixo de 1
    component.prevStep();
    expect(component.currentStep()).toBe(1);
  });

  it('emite evento de dismiss quando onDismiss é chamado', () => {
    let dismissed = false;
    component.dismiss.subscribe(() => {
      dismissed = true;
    });

    component.onDismiss();
    expect(dismissed).toBe(true);
  });
});
