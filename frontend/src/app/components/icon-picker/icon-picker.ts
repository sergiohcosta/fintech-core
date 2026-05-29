import { Component, input, output, signal, computed } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

export const AVAILABLE_ICONS = [
  'shopping_cart', 'restaurant', 'directions_car', 'home', 'build',
  'medical_services', 'school', 'fitness_center', 'flight', 'local_gas_station',
  'payments', 'account_balance', 'savings', 'trending_up', 'work',
  'pets', 'redeem', 'videogame_asset', 'subscriptions', 'electrical_services',
  'face', 'family_restroom', 'celebration', 'movie', 'checkroom'
] as const;

@Component({
  selector: 'app-icon-picker',
  standalone: true,
  imports: [MatIconModule],
  templateUrl: './icon-picker.html',
  styleUrl: './icon-picker.scss'
})
export class IconPicker {
  selectedIcon = input<string>('folder');
  disabled = input<boolean>(false);
  label = input<string>('Ícone');

  iconSelected = output<string>();

  isOpen = signal(false);
  searchTerm = signal('');

  readonly availableIcons = AVAILABLE_ICONS as unknown as string[];

  filteredIcons = computed(() => {
    const term = this.searchTerm().toLowerCase();
    if (!term) return this.availableIcons;
    return this.availableIcons.filter(i => i.includes(term));
  });

  toggle(): void {
    if (!this.disabled()) {
      this.isOpen.update(open => !open);
    }
  }

  onSearch(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.searchTerm.set(target.value);
  }

  select(icon: string): void {
    this.iconSelected.emit(icon);
    this.isOpen.set(false);
    this.searchTerm.set('');
  }
}
