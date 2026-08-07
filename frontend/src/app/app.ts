import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { formatVersionLabel, formatVersionTooltip, readAppEnv } from './core/app-env';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  // Estático: a config de runtime não muda durante a vida da página, então não
  // precisa de signal — basta ler uma vez.
  private readonly appEnv = readAppEnv();
  protected readonly versionLabel = formatVersionLabel(this.appEnv);
  // Data/hora do commit só no tooltip — o rodapé global é discreto de propósito.
  protected readonly versionTooltip = formatVersionTooltip(this.appEnv);
}
