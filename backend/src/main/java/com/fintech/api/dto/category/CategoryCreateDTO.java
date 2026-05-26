package com.fintech.api.dto.category;

import java.util.UUID;

public record CategoryCreateDTO(
        String name,
        String icon,
        String color,
        UUID parentId // Pode ser nulo se for raiz
) {
}