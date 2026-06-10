package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCategorySeederTest {

    @Mock
    CategoryRepository categoryRepository;

    @InjectMocks
    DefaultCategorySeeder seeder;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Tenant Teste");

        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("seedForTenant cria exatamente 66 categorias")
    void seedForTenant_creates66Categories() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        assertThat(captor.getValue()).hasSize(66);
    }

    @Test
    @DisplayName("seedForTenant cria exatamente 14 categorias raiz")
    void seedForTenant_creates14RootCategories() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        long rootCount = captor.getValue().stream()
                .filter(c -> c.getParent() == null)
                .count();
        assertThat(rootCount).isEqualTo(14);
    }

    @Test
    @DisplayName("todas as 66 categorias têm taxonomy_code não nulo")
    void seedForTenant_allCategoriesHaveTaxonomyCode() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        assertThat(captor.getValue())
                .allSatisfy(c -> assertThat(c.getTaxonomyCode()).isNotNull());
    }

    @Test
    @DisplayName("nenhuma categoria tem tenant nulo")
    void seedForTenant_allCategoriesHaveTenant() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        assertThat(captor.getValue())
                .allSatisfy(c -> assertThat(c.getTenant()).isEqualTo(tenant));
    }

    @Test
    @DisplayName("saveAll é chamado exatamente uma vez (batch único)")
    void seedForTenant_callsSaveAllOnce() {
        seeder.seedForTenant(tenant);

        verify(categoryRepository, times(1)).saveAll(anyList());
    }
}
