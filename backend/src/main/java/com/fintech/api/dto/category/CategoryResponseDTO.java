package com.fintech.api.dto.category;

import com.fintech.api.domain.category.Category;
import java.util.List;
import java.util.UUID;

public record CategoryResponseDTO(
        UUID id,
        String name,
        String icon,
        String color,
        UUID parentId,
        boolean archived,
        String taxonomyCode,
        List<CategoryResponseDTO> children) {

    // Sem includeArchived: filtra filhos arquivados (comportamento padrão)
    public static CategoryResponseDTO fromEntity(Category category) {
        return fromEntity(category, false);
    }

    public static CategoryResponseDTO fromEntity(Category category, boolean includeArchived) {
        return new CategoryResponseDTO(
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getParent() != null ? category.getParent().getId() : null,
                category.getDeletedAt() != null,
                category.getTaxonomyCode(),
                category.getChildren().stream()
                        .filter(c -> includeArchived || c.getDeletedAt() == null)
                        .map(c -> fromEntity(c, includeArchived))
                        .toList());
    }
}
