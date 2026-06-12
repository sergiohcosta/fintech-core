import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';

@Component({
  selector: 'app-planning-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet, MatTabsModule],
  template: `
    <mat-tab-nav-bar [tabPanel]="panel">
      <a mat-tab-link routerLink="current"   routerLinkActive #c="routerLinkActive"  [active]="c.isActive">Ciclo atual</a>
      <a mat-tab-link routerLink="cycles"    routerLinkActive #h="routerLinkActive"  [active]="h.isActive">Histórico</a>
      <a mat-tab-link routerLink="recurring" routerLinkActive #r="routerLinkActive"  [active]="r.isActive">Recorrentes</a>
    </mat-tab-nav-bar>
    <mat-tab-nav-panel #panel>
      <router-outlet />
    </mat-tab-nav-panel>
  `,
})
export class PlanningShellComponent {}
