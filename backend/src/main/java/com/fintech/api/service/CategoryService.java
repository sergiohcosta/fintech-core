package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.category.CategoryCreateDTO;
import com.fintech.api.dto.category.CategoryResponseDTO;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.exception.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository repository;

    @Transactional(readOnly = true)
    public List<CategoryResponseDTO> findAllRoots(User user) {
        // Busca baseada no TENANT do usuário logado
        return repository.findAllByTenantIdAndParentIsNull(user.getTenant().getId())
                .stream()
                .map(CategoryResponseDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponseDTO findById(UUID id, User user) {
        Category category = repository.findByIdAndTenantId(id, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
        return CategoryResponseDTO.fromEntity(category);
    }

    @Transactional
    public CategoryResponseDTO create(CategoryCreateDTO dto, User user) {
        Tenant tenant = user.getTenant();

        if (repository.existsByNameAndTenantIdAndParentId(dto.name(), tenant.getId(), dto.parentId())) {
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
            Category parent = repository.findByIdAndTenantId(dto.parentId(), tenant.getId())
                    .orElseThrow(() -> new EntityNotFoundException("Categoria pai não encontrada."));
            parent.addChild(category);
        }

        return CategoryResponseDTO.fromEntity(repository.save(category));
    }

    @Transactional
    public CategoryResponseDTO update(UUID id, CategoryCreateDTO dto, User user) {
        Category category = repository.findByIdAndTenantId(id, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));

        String oldIcon  = category.getIcon();
        String oldColor = category.getColor();

        category.setName(dto.name());
        category.setIcon(dto.icon());
        category.setColor(dto.color());

        if (dto.parentId() != null) {
            Category parent = repository.findByIdAndTenantId(dto.parentId(), user.getTenant().getId())
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

    // Recursão em profundidade sobre a árvore de filhos (lazy load por nível).
    private void propagateToDescendants(Category category, String icon, String color) {
        for (Category child : category.getChildren()) {
            child.setIcon(icon);
            child.setColor(color);
            propagateToDescendants(child, icon, color);
        }
    }

    @Transactional
    public void delete(UUID id, User user) {
        Category category = repository.findByIdAndTenantId(id, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
        repository.delete(category);
    }
}