import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { formatVersionLabel, readAppEnv } from './core/app-env';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  // Estático: a config de runtime não muda durante a vida da página, então não
  // precisa de signal — basta ler uma vez.
  protected readonly versionLabel = formatVersionLabel(readAppEnv());
}
