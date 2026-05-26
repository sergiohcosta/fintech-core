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
        List<CategoryResponseDTO> children) {
    public static CategoryResponseDTO fromEntity(Category category) {
        return new CategoryResponseDTO(
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getParent() != null ? category.getParent().getId() : null,
                category.getChildren().stream()
                        .map(CategoryResponseDTO::fromEntity)
                        .toList());
    }
}