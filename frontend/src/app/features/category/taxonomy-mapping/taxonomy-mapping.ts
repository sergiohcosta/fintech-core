import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { finalize } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../core/services/auth';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { CategoryResponseDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { getSuggestion, TAXONOMY_ROOTS, TaxonomyRoot } from './taxonomy-suggestions';

@Component({
  selector: 'app-taxonomy-mapping',
  standalone: true,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './taxonomy-mapping.html',
  styleUrl: './taxonomy-mapping.scss',
})
export class TaxonomyMappingComponent implements OnInit {
  private router = inject(Router);
  private authService = inject(AuthService);
  private categoriesService = inject(CategoriesService);
  private snackBar = inject(MatSnackBar);

  readonly taxonomyRoots: TaxonomyRoot[] = TAXONOMY_ROOTS;

  rootCategories = signal<CategoryResponseDTO[]>([]);
  selectedCategory = signal<CategoryResponseDTO | null>(null);
  // Map: categoryId → taxonomyCode pendente (ainda não salvo)
  pendingMappings = signal<Map<string, string>>(new Map());
  loading = signal(true);
  saving = signal(false);

  // Conjunto de todos os códigos já em uso (salvos + pendentes)
  usedCodes = computed(() => {
    const pending = this.pendingMappings();
    // exclui o código salvo de categorias que já têm override pendente
    const saved = this.rootCategories()
      .filter(c => c.taxonomyCode && !pending.has(c.id))
      .map(c => c.taxonomyCode!);
    return new Set([...saved, ...pending.values()]);
  });

  // Sugestão automática para a categoria selecionada
  suggestedCode = computed(() => {
    const cat = this.selectedCategory();
    return cat ? getSuggestion(cat.name) : null;
  });

  // Quantas categorias já têm código (salvo ou pendente)
  mappedCount = computed(() => {
    const pending = this.pendingMappings();
    return this.rootCategories().filter(c => c.taxonomyCode || pending.has(c.id)).length;
  });

  ngOnInit(): void {
    if (!this.authService.isAdmin()) {
      this.router.navigate(['/dashboard']);
      return;
    }
    this.categoriesService.listCategories({ includeArchived: false }).subscribe({
      next: (cats) => {
        // listCategories retorna árvore completa; filtramos apenas as raízes
        this.rootCategories.set(cats.filter(c => !c.parentId));
        this.loading.set(false);
        this.autoSelectNext();
      },
      error: () => this.loading.set(false),
    });
  }

  selectCategory(cat: CategoryResponseDTO): void {
    this.selectedCategory.set(cat);
  }

  selectCode(code: string): void {
    const cat = this.selectedCategory();
    if (!cat) return;
    // Não permite selecionar código em uso por OUTRA categoria
    if (this.isCodeUsedByOther(code)) return;

    // Nova referência de Map para disparar recompute nos computed()
    this.pendingMappings.update(m => new Map(m).set(cat.id, code));
    this.autoSelectNext();
  }

  // Retorna true se o código está em uso por uma categoria diferente da selecionada
  isCodeUsedByOther(code: string): boolean {
    const cat = this.selectedCategory();
    const currentCode = cat
      ? (this.pendingMappings().get(cat.id) ?? cat.taxonomyCode ?? null)
      : null;
    return this.usedCodes().has(code) && code !== currentCode;
  }

  // Código efetivo de uma categoria: pendente > salvo > null
  getEffectiveCode(cat: CategoryResponseDTO): string | null {
    return this.pendingMappings().get(cat.id) ?? cat.taxonomyCode ?? null;
  }

  hasSuggestion(cat: CategoryResponseDTO): boolean {
    return getSuggestion(cat.name) !== null;
  }

  save(): void {
    const mappings = this.pendingMappings();
    if (!mappings.size) return;

    const calls = [...mappings.entries()].flatMap(([id, taxonomyCode]) => {
      const cat = this.rootCategories().find(c => c.id === id);
      if (!cat) return [];
      return [this.categoriesService.updateCategory(id, {
        name: cat.name,
        icon: cat.icon,
        color: cat.color,
        parentId: cat.parentId ?? null,
        taxonomyCode,
      })];
    });

    this.saving.set(true);
    forkJoin(calls).pipe(
      finalize(() => this.saving.set(false))
    ).subscribe({
      next: (updated) => {
        // Atualiza rootCategories com os valores retornados pelo backend
        this.rootCategories.update(cats =>
          cats.map(c => updated.find(r => r.id === c.id) ?? c)
        );
        this.pendingMappings.set(new Map());
        this.snackBar.open('Mapeamento salvo com sucesso.', 'OK', { duration: 3000 });
      },
      error: () => {
        this.snackBar.open('Erro ao salvar mapeamento.', 'Fechar', { duration: 4000 });
      },
    });
  }

  private autoSelectNext(): void {
    const pending = this.pendingMappings();
    const next = this.rootCategories().find(
      c => !c.taxonomyCode && !pending.has(c.id)
    );
    this.selectedCategory.set(next ?? null);
  }
}
