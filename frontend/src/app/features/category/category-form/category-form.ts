import { Component, inject, OnInit, signal, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { CategoriesService } from '../../../core/api/categories/categories.service';
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

interface CategoryOption {
  id: string;
  name: string;
  level: number;
  color: string;
  icon: string;
}

@Component({
  selector: 'app-category-form',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSelectModule,
    MatIconModule,
    MatSnackBarModule,
    RouterLink
  ],
  templateUrl: './category-form.html',
  styleUrl: './category-form.scss'
})
export class CategoryForm implements OnInit {
  private fb = inject(FormBuilder);
  private service = inject(CategoriesService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);
  private destroyRef = inject(DestroyRef);

  form!: FormGroup;
  isEditMode = signal(false);
  categoryId = signal<string | null>(null);
  parentOptions = signal<CategoryOption[]>([]);
  iconPickerOpen = signal(false);
  inherited = signal(false);
  selectedIcon = signal('folder');
  inheritedFromName = signal<string | null>(null);

  availableIcons = [
    'shopping_cart', 'restaurant', 'directions_car', 'home', 'build',
    'medical_services', 'school', 'fitness_center', 'flight', 'local_gas_station',
    'payments', 'account_balance', 'savings', 'trending_up', 'work',
    'pets', 'redeem', 'videogame_asset', 'subscriptions', 'electrical_services',
    'face', 'family_restroom', 'celebration', 'movie', 'checkroom'
  ];

  ngOnInit(): void {
    this.initForm();
    this.setupParentInheritance();
    this.loadData();
  }

  private initForm() {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(3)]],
      icon: ['folder', Validators.required],
      color: ['#3f51b5', Validators.required],
      parentId: [null]
    });
  }

  private setupParentInheritance() {
    this.form.get('parentId')!.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((parentId: string | null) => {
        if (parentId) {
          const parent = this.parentOptions().find(opt => opt.id === parentId);
          if (parent) {
            this.form.patchValue({ icon: parent.icon, color: parent.color }, { emitEvent: false });
            this.form.get('icon')!.disable({ emitEvent: false });
            this.form.get('color')!.disable({ emitEvent: false });
            this.inherited.set(true);
            this.inheritedFromName.set(parent.name);
            this.selectedIcon.set(parent.icon);
          }
        } else {
          this.form.get('icon')!.enable({ emitEvent: false });
          this.form.get('color')!.enable({ emitEvent: false });
          this.form.patchValue({ icon: 'folder', color: '#3f51b5' }, { emitEvent: false });
          this.inherited.set(false);
          this.inheritedFromName.set(null);
          this.selectedIcon.set('folder');
        }
      });
  }

  private loadData() {
    const id = this.route.snapshot.paramMap.get('id');

    if (id) {
      // Modo edição: aguarda ambos para garantir que parentOptions esteja
      // populado antes do patchValue disparar o valueChanges de herança
      this.isEditMode.set(true);
      this.categoryId.set(id);

      forkJoin({
        categories: this.service.listCategories(),
        cat: this.service.getCategory(id),
      }).subscribe({
        next: ({ categories, cat }) => {
          this.buildParentOptions(categories);
          this.form.patchValue(cat, { emitEvent: false });
          this.selectedIcon.set(cat.icon);
        },
        error: () => this.showError('Erro ao carregar categoria.'),
      });
    } else {
      // Modo criação: carrega apenas as categorias disponíveis como pai
      this.service.listCategories().subscribe({
        next: (categories) => this.buildParentOptions(categories),
        error: () => this.showError('Erro ao carregar categorias.'),
      });
    }
  }

  private buildParentOptions(categories: CategoryResponseDTO[]) {
    const flattened: CategoryOption[] = [];
    this.flattenCategories(categories, flattened, 0);
    if (this.isEditMode() && this.categoryId()) {
      this.parentOptions.set(flattened.filter(opt => opt.id !== this.categoryId()));
    } else {
      this.parentOptions.set(flattened);
    }
  }

  private flattenCategories(categories: CategoryResponseDTO[], result: CategoryOption[], level: number) {
    categories.forEach(cat => {
      if (this.isEditMode() && cat.id === this.categoryId()) return;
      result.push({ id: cat.id, name: cat.name, level, color: cat.color, icon: cat.icon });
      if (cat.children?.length) {
        this.flattenCategories(cat.children, result, level + 1);
      }
    });
  }

  selectIcon(icon: string) {
    this.form.get('icon')!.setValue(icon);
    this.selectedIcon.set(icon);
    this.iconPickerOpen.set(false);
  }

  toggleIconPicker() {
    if (!this.inherited()) {
      this.iconPickerOpen.update(open => !open);
    }
  }

  onSubmit() {
    if (this.form.invalid) return;
    const data = this.form.getRawValue();
    const request = (this.isEditMode() && this.categoryId())
      ? this.service.updateCategory(this.categoryId()!, data)
      : this.service.createCategory(data);

    request.subscribe({
      next: () => this.onSuccess('Categoria salva com sucesso!'),
      error: () => this.showError('Erro ao salvar categoria.')
    });
  }

  private onSuccess(msg: string) {
    this.snackBar.open(msg, 'OK', { duration: 3000 });
    this.router.navigate(['/categories']);
  }

  private showError(msg: string) {
    this.snackBar.open(msg, 'Fechar', { duration: 5000 });
  }
}
