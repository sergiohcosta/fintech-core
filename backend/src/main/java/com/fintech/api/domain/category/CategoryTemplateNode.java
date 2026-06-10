package com.fintech.api.domain.category;

import java.util.List;

public record CategoryTemplateNode(
        String name,
        String icon,
        String color,
        CategoryTaxonomy taxonomy,
        List<CategoryTemplateNode> children
) {
    // Construtor conveniente para nós-folha (sem filhos)
    public CategoryTemplateNode(String name, String icon, String color, CategoryTaxonomy taxonomy) {
        this(name, icon, color, taxonomy, List.of());
    }
}
