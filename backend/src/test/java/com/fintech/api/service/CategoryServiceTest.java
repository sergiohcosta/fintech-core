package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.category.CategoryCreateDTO;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock CategoryRepository repository;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks CategoryService service;

    // ─── update: propagação de ícone e cor ────────────────────────────────

    @Test
    @DisplayName("update propaga ícone e cor para filhos diretos quando mudam")
    void update_propagatesIconAndColorToDirectChildren() {
        User user = buildUser();

        Category child = buildCategory("Filho", "shopping_cart", "#ff0000");
        Category parent = buildCategory("Pai", "folder", "#3f51b5");
        parent.getChildren().add(child);

        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(parent.getId(), user.getTenant().getId()))
                .thenReturn(Optional.of(parent));
        when(repository.save(any())).thenReturn(parent);

        CategoryCreateDTO dto = new CategoryCreateDTO("Pai", "home", "#00ff00", null, null);
        service.update(parent.getId(), dto, user);

        assertThat(child.getIcon()).isEqualTo("home");
        assertThat(child.getColor()).isEqualTo("#00ff00");
    }

    @Test
    @DisplayName("update propaga ícone e cor recursivamente até os netos")
    void update_propagatesIconAndColorToGrandchildren() {
        User user = buildUser();

        Category grandchild = buildCategory("Neto", "old_icon", "#111111");
        Category child = buildCategory("Filho", "old_icon", "#111111");
        child.getChildren().add(grandchild);
        Category parent = buildCategory("Pai", "folder", "#3f51b5");
        parent.getChildren().add(child);

        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(parent.getId(), user.getTenant().getId()))
                .thenReturn(Optional.of(parent));
        when(repository.save(any())).thenReturn(parent);

        CategoryCreateDTO dto = new CategoryCreateDTO("Pai", "star", "#abcdef", null, null);
        service.update(parent.getId(), dto, user);

        assertThat(child.getIcon()).isEqualTo("star");
        assertThat(child.getColor()).isEqualTo("#abcdef");
        assertThat(grandchild.getIcon()).isEqualTo("star");
        assertThat(grandchild.getColor()).isEqualTo("#abcdef");
    }

    @Test
    @DisplayName("update NÃO propaga quando apenas o nome muda")
    void update_doesNotPropagateWhenOnlyNameChanges() {
        User user = buildUser();

        Category child = buildCategory("Filho", "shopping_cart", "#ff0000");
        Category parent = buildCategory("Pai", "folder", "#3f51b5");
        parent.getChildren().add(child);

        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(parent.getId(), user.getTenant().getId()))
                .thenReturn(Optional.of(parent));
        when(repository.save(any())).thenReturn(parent);

        // Mesmo ícone e cor do pai, só o nome muda
        CategoryCreateDTO dto = new CategoryCreateDTO("Pai Renomeado", "folder", "#3f51b5", null, null);
        service.update(parent.getId(), dto, user);

        assertThat(child.getIcon()).isEqualTo("shopping_cart");
        assertThat(child.getColor()).isEqualTo("#ff0000");
    }

    @Test
    @DisplayName("update em categoria sem filhos executa sem erros")
    void update_noChildrenIsNoop() {
        User user = buildUser();
        Category leaf = buildCategory("Folha", "folder", "#3f51b5");

        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(leaf.getId(), user.getTenant().getId()))
                .thenReturn(Optional.of(leaf));
        when(repository.save(any())).thenReturn(leaf);

        CategoryCreateDTO dto = new CategoryCreateDTO("Folha", "home", "#ffffff", null, null);
        service.update(leaf.getId(), dto, user);

        assertThat(leaf.getIcon()).isEqualTo("home");
        assertThat(leaf.getColor()).isEqualTo("#ffffff");
    }

    // ─── update: taxonomyCode ────────────────────────────────────────────────────

    @Test
    @DisplayName("update persiste taxonomyCode quando usuário é ADMIN")
    void update_persistsTaxonomyCodeForAdmin() {
        User admin = buildAdmin();
        Category category = buildCategory("Moradia", "home", "#3f51b5");
        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(category.getId(), admin.getTenant().getId()))
                .thenReturn(Optional.of(category));
        when(repository.save(any())).thenReturn(category);

        CategoryCreateDTO dto = new CategoryCreateDTO("Moradia", "home", "#3f51b5", null, "HOUSING");
        service.update(category.getId(), dto, admin);

        assertThat(category.getTaxonomyCode()).isEqualTo("HOUSING");
    }

    @Test
    @DisplayName("update lança AccessDeniedException quando não-ADMIN tenta definir taxonomyCode")
    void update_throwsAccessDeniedForNonAdmin() {
        User user = buildUser();
        Category category = buildCategory("Moradia", "home", "#3f51b5");
        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(category.getId(), user.getTenant().getId()))
                .thenReturn(Optional.of(category));

        CategoryCreateDTO dto = new CategoryCreateDTO("Moradia", "home", "#3f51b5", null, "HOUSING");

        assertThatThrownBy(() -> service.update(category.getId(), dto, user))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("update NÃO altera taxonomyCode quando dto.taxonomyCode() é null")
    void update_doesNotAlterTaxonomyCodeWhenNull() {
        User admin = buildAdmin();
        Category category = buildCategory("Moradia", "home", "#3f51b5");
        category.setTaxonomyCode("HOUSING");
        when(repository.findByIdAndTenantIdAndDeletedAtIsNull(category.getId(), admin.getTenant().getId()))
                .thenReturn(Optional.of(category));
        when(repository.save(any())).thenReturn(category);

        CategoryCreateDTO dto = new CategoryCreateDTO("Moradia Renomeada", "home", "#3f51b5", null, null);
        service.update(category.getId(), dto, admin);

        assertThat(category.getTaxonomyCode()).isEqualTo("HOUSING");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        user.setRole(UserRole.USER);
        return user;
    }

    private User buildAdmin() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User admin = new User();
        admin.setTenant(tenant);
        admin.setRole(UserRole.ADMIN);
        return admin;
    }

    private Category buildCategory(String name, String icon, String color) {
        return Category.builder()
                .id(UUID.randomUUID())
                .name(name)
                .icon(icon)
                .color(color)
                .tenant(new Tenant())
                .build();
    }
}
