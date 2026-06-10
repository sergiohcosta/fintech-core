package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.category.CategoryCreateDTO;
import com.fintech.api.dto.category.CategoryResponseDTO;
import com.fintech.api.exception.CategoryHasTransactionsException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> findAllRoots(User user, boolean includeArchived) {
        UUID tenantId = user.getTenant().getId();
        List<Category> roots = includeArchived
                ? repository.findAllByTenantIdAndParentIsNull(tenantId)
                : repository.findAllByTenantIdAndParentIsNullAndDeletedAtIsNull(tenantId);
        return roots.stream()
                .map(c -> CategoryResponseDTO.fromEntity(c, includeArchived))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponseDTO findById(UUID id, User user) {
        Category category = repository.findByIdAndTenantIdAndDeletedAtIsNull(id, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
        return CategoryResponseDTO.fromEntity(category);
    }

    @Transactional
    public CategoryResponseDTO create(CategoryCreateDTO dto, User user) {
        Tenant tenant = user.getTenant();

        if (repository.existsByNameAndTenantIdAndParentIdAndDeletedAtIsNull(dto.name(), tenant.getId(), dto.parentId())) {
            throw new IllegalArgumentException("Categoria já existe neste nível.");
        }

        Category category = Category.builder()
                .name(dto.name())
                .icon(dto.icon())
                .color(dto.color())
                .tenant(tenant)
                .createdBy(user)
                .build();

        if (dto.parentId() != null) {
            Category parent = repository.findByIdAndTenantIdAndDeletedAtIsNull(dto.parentId(), tenant.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Categoria pai não encontrada."));
            parent.addChild(category);
        }

        return CategoryResponseDTO.fromEntity(repository.save(category));
    }

    @Transactional
    public CategoryResponseDTO update(UUID id, CategoryCreateDTO dto, User user) {
        Category category = repository.findByIdAndTenantIdAndDeletedAtIsNull(id, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));

        String oldIcon  = category.getIcon();
        String oldColor = category.getColor();

        category.setName(dto.name());
        category.setIcon(dto.icon());
        category.setColor(dto.color());

        if (dto.taxonomyCode() != null) {
            // taxonomyCode é campo de curadoria global — somente ADMIN pode defini-lo.
            // AccessDeniedException é interceptada pelo ExceptionTranslationFilter do Spring
            // Security e convertida automaticamente em 403, sem necessidade de handler explícito.
            if (user.getRole() != UserRole.ADMIN) {
                throw new AccessDeniedException("Somente ADMIN pode definir taxonomy_code");
            }
            category.setTaxonomyCode(dto.taxonomyCode());
        }

        if (dto.parentId() != null) {
            Category parent = repository.findByIdAndTenantIdAndDeletedAtIsNull(dto.parentId(), user.getTenant().getId())
                    .orElseThrow(() -> new EntityNotFoundException("Categoria pai não encontrada."));
            category.setParent(parent);
        } else {
            category.setParent(null);
        }

        // Propaga para todos os descendentes quando ícone ou cor mudam.
        // Os filhos são entidades gerenciadas — dirty checking do JPA persiste
        // as alterações automaticamente no flush, sem save() explícito.
        if (!oldIcon.equals(dto.icon()) || !oldColor.equals(dto.color())) {
            propagateToDescendants(category, dto.icon(), dto.color());
        }

        return CategoryResponseDTO.fromEntity(repository.save(category));
    }

    @Transactional
    public void delete(UUID id, User user) {
        UUID tenantId = user.getTenant().getId();
        Category category = repository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));

        List<UUID> subtreeIds = collectSubtreeIds(category);
        long count = transactionRepository.countByCategoryIdInAndTenantId(subtreeIds, tenantId);

        if (count > 0) {
            // O frontend trata o 409 exibindo o modal com as opções de archive/mover
            throw new CategoryHasTransactionsException(count);
        }

        // Nenhuma transação associada — soft delete em cascata na subárvore inteira
        softDeleteSubtree(category, LocalDateTime.now());
    }

    @Transactional
    public void archive(UUID id, UUID targetCategoryId, User user) {
        UUID tenantId = user.getTenant().getId();
        Category category = repository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));

        List<UUID> subtreeIds = collectSubtreeIds(category);

        if (targetCategoryId != null) {
            if (subtreeIds.contains(targetCategoryId)) {
                throw new IllegalArgumentException(
                        "Não é possível mover transações para uma subcategoria da categoria sendo arquivada.");
            }
            repository.findByIdAndTenantIdAndDeletedAtIsNull(targetCategoryId, tenantId)
                    .orElseThrow(() -> new EntityNotFoundException("Categoria de destino não encontrada."));

            transactionRepository.reassignCategories(subtreeIds, targetCategoryId, tenantId);
        }

        softDeleteSubtree(category, LocalDateTime.now());
    }

    // --- helpers privados ---

    // Coleta IDs de toda a subárvore (raiz + todos os descendentes via lazy load por nível)
    private List<UUID> collectSubtreeIds(Category category) {
        List<UUID> ids = new ArrayList<>();
        ids.add(category.getId());
        for (Category child : category.getChildren()) {
            ids.addAll(collectSubtreeIds(child));
        }
        return ids;
    }

    // Marca deleted_at em toda a subárvore. O dirty checking do JPA persiste
    // as alterações sem save() explícito pois o método é @Transactional.
    private void softDeleteSubtree(Category category, LocalDateTime now) {
        category.setDeletedAt(now);
        for (Category child : category.getChildren()) {
            softDeleteSubtree(child, now);
        }
    }

    // Recursão em profundidade sobre a árvore de filhos (lazy load por nível).
    private void propagateToDescendants(Category category, String icon, String color) {
        for (Category child : category.getChildren()) {
            child.setIcon(icon);
            child.setColor(color);
            propagateToDescendants(child, icon, color);
        }
    }
}
