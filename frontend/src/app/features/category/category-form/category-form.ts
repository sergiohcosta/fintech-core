import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { CategoryService } from '../../../core/services/category';
import { CategoryModel } from '../../../core/models/category';

interface CategoryOption {
  id: string;
  name: string;
  level: number;
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
  private service = inject(CategoryService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);

  form!: FormGroup;
  isEditMode = signal(false);
  categoryId = signal<string | null>(null);
  parentOptions = signal<CategoryOption[]>([]);

  availableIcons = [
    'shopping_cart', 'restaurant', 'directions_car', 'home', 'build', 
    'medical_services', 'school', 'fitness_center', 'flight', 'local_gas_station', 
    'payments', 'account_balance', 'savings', 'trending_up', 'work', 
    'pets', 'redeem', 'videogame_asset', 'subscriptions', 'electrical_services',
    'face', 'family_restroom', 'celebration', 'movie', 'checkroom'
  ];


  ngOnInit(): void {
    this.initForm();
    this.checkEditMode();
    this.loadParentOptions();
  }

  private initForm() {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(3)]],
      icon: ['folder', Validators.required],
      color: ['#3f51b5', Validators.required],
      parentId: [null]
    });
  }

  private loadParentOptions() {
    this.service.list().subscribe(categories => {
      const flattened: CategoryOption[] = [];
      this.flattenCategories(categories, flattened, 0);
      
      if (this.isEditMode() && this.categoryId()) {
        this.parentOptions.set(flattened.filter(opt => opt.id !== this.categoryId()));
      } else {
        this.parentOptions.set(flattened);
      }
    });
  }

  private flattenCategories(categories: CategoryModel[], result: CategoryOption[], level: number) {
    categories.forEach(cat => {
      if (this.isEditMode() && cat.id === this.categoryId()) {
        return; 
      }
      result.push({ id: cat.id, name: cat.name, level });
      if (cat.children && cat.children.length > 0) {
        this.flattenCategories(cat.children, result, level + 1);
      }
    });
  }

  private checkEditMode() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.categoryId.set(id);
      this.service.getById(id).subscribe({
        next: (cat) => this.form.patchValue(cat),
        error: () => this.showError('Erro ao carregar categoria.')
      });
    }
  }

  onSubmit() {
    if (this.form.invalid) return;
    const data = this.form.value;
    const request = (this.isEditMode() && this.categoryId())
      ? this.service.update(this.categoryId()!, data)
      : this.service.create(data);

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
