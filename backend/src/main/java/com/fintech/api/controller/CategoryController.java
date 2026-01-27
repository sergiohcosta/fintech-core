package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.category.CategoryCreateDTO;
import com.fintech.api.dto.category.CategoryResponseDTO;
import com.fintech.api.service.CategoryService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService service;

    @GetMapping
    public ResponseEntity<List<CategoryResponseDTO>> listAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.findAllRoots(user));
    }

    @PostMapping
    public ResponseEntity<CategoryResponseDTO> create(@RequestBody CategoryCreateDTO dto,
            @AuthenticationPrincipal User user) {
        CategoryResponseDTO newCategory = service.create(dto, user);

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(newCategory.id())
                .toUri();

        return ResponseEntity.created(uri).body(newCategory);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponseDTO> findById(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.findById(id, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        service.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}