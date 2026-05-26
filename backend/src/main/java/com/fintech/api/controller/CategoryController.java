package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.category.CategoryCreateDTO;
import com.fintech.api.dto.category.CategoryResponseDTO;
import com.fintech.api.openapi.CategoriesApi;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CategoryController implements CategoriesApi {

    private final CategoryService service;
    private final UserRepository userRepository;

    @Override
    @GetMapping
    public ResponseEntity<List<CategoryResponseDTO>> listCategories() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(service.findAllRoots(user));
    }

    @Override
    @PostMapping
    public ResponseEntity<CategoryResponseDTO> createCategory(@Valid @RequestBody CategoryCreateDTO categoryCreateDTO) {
        User user = getAuthenticatedUser();
        CategoryResponseDTO newCategory = service.create(categoryCreateDTO, user);

        URI uri = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(newCategory.id())
                .toUri();

        return ResponseEntity.created(uri).body(newCategory);
    }

    @Override
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponseDTO> updateCategory(
            @PathVariable UUID id,
            @Valid @RequestBody CategoryCreateDTO categoryCreateDTO) {
        User user = getAuthenticatedUser();
        CategoryResponseDTO updatedCategory = service.update(id, categoryCreateDTO, user);
        return ResponseEntity.ok(updatedCategory);
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<CategoryResponseDTO> getCategory(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(service.findById(id, user));
    }

    @Override
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        service.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    // Obtém o usuário autenticado via SecurityContextHolder em vez de @AuthenticationPrincipal,
    // pois a interface OpenAPI não inclui esse parâmetro extra nas assinaturas dos métodos.
    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
