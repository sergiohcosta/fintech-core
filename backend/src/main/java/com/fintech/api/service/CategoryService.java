package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.category.CategoryCreateDTO;
import com.fintech.api.dto.category.CategoryResponseDTO;
import com.fintech.api.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
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
    public void delete(UUID id, User user) {
        Category category = repository.findByIdAndTenantId(id, user.getTenant().getId())
                .orElseThrow(() -> new EntityNotFoundException("Categoria não encontrada."));
        repository.delete(category);
    }
}