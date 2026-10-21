import { Component, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';

@Component({
  selector: 'app-welcome-wizard',
  standalone: true,
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './welcome-wizard.html',
  styleUrl: './welcome-wizard.scss',
})
export class WelcomeWizardComponent {
  dismiss = output<void>();

  currentStep = signal<number>(1);
  totalSteps = 3;

  nextStep(): void {
    if (this.currentStep() < this.totalSteps) {
      this.currentStep.update(s => s + 1);
    }
  }

  prevStep(): void {
    if (this.currentStep() > 1) {
      this.currentStep.update(s => s - 1);
    }
  }

  onDismiss(): void {
    this.dismiss.emit();
  }
}
