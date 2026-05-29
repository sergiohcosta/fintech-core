import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDividerModule } from '@angular/material/divider';
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';

export interface CategoryArchiveDialogData {
  category: CategoryResponseDTO;
  transactionCount: number;
  availableCategories: CategoryResponseDTO[];
}

export type CategoryArchiveDialogResult =
  | { action: 'ARCHIVE' }
  | { action: 'MOVE'; targetCategoryId: string };

@Component({
  selector: 'app-category-archive-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatSelectModule,
    MatFormFieldModule,
    MatDividerModule,
  ],
  templateUrl: './category-archive-dialog.html',
  styleUrl: './category-archive-dialog.scss',
})
export class CategoryArchiveDialogComponent {
  readonly data = inject<CategoryArchiveDialogData>(MAT_DIALOG_DATA);
  readonly dialogRef = inject(MatDialogRef<CategoryArchiveDialogComponent>);

  selectedTargetId = signal<string | null>(null);

  onArchive(): void {
    this.dialogRef.close({ action: 'ARCHIVE' } as CategoryArchiveDialogResult);
  }

  onMove(): void {
    const targetId = this.selectedTargetId();
    if (!targetId) return;
    this.dialogRef.close({ action: 'MOVE', targetCategoryId: targetId } as CategoryArchiveDialogResult);
  }

  onCancel(): void {
    this.dialogRef.close(undefined);
  }
}
