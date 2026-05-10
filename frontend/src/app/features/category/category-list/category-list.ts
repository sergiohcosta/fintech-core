import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Router, RouterLink } from '@angular/router';

import { CategoryService } from '../../../core/services/category';
import { CategoryModel } from '../../../core/models/category';
import { ConfirmationDialogComponent } from '../../../components/confirmation-dialog/confirmation-dialog';

interface FlatCategory extends CategoryModel {
  level: number;
}

@Component({
  selector: 'app-category-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatTooltipModule,
    MatSnackBarModule,
    RouterLink
  ],
  templateUrl: './category-list.html',
  styleUrl: './category-list.scss'
})
export class CategoryList implements OnInit {
  private router = inject(Router);
  private service = inject(CategoryService);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);

  categories = signal<FlatCategory[]>([]);
  displayedColumns: string[] = ['name', 'icon', 'actions'];

  ngOnInit(): void {
    this.loadCategories();
  }

  loadCategories() {
    this.service.list().subscribe({
      next: (data) => {
        const flattened: FlatCategory[] = [];
        this.flattenCategories(data, flattened, 0);
        this.categories.set(flattened);
      },
      error: (err) => console.error('Erro ao carregar categorias:', err)
    });
  }

  private flattenCategories(categories: CategoryModel[], result: FlatCategory[], level: number) {
    categories.forEach(cat => {
      result.push({ ...cat, level });
      if (cat.children && cat.children.length > 0) {
        this.flattenCategories(cat.children, result, level + 1);
      }
    });
  }

  onEdit(category: CategoryModel) {
    this.router.navigate(['/categories', category.id]);
  }

  onDelete(category: CategoryModel) {
    const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
      width: '400px',
      data: {
        title: 'Excluir Categoria',
        message: 'Tem certeza que deseja remover esta categoria e suas subcategorias?',
        confirmText: 'Sim, excluir'
      }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result === true) {
        this.service.delete(category.id).subscribe({
          next: () => {
            this.snackBar.open('Categoria excluída com sucesso!', 'OK', { duration: 3000 });
            this.loadCategories();
          },
          error: (err) => {
            console.error('Erro ao excluir categoria:', err);
            this.snackBar.open('Erro ao excluir categoria.', 'Fechar', { duration: 5000 });
          }
        });
      }
    });
  }
}
