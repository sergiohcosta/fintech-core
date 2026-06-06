package com.fintech.api.service;

import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MembersServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks MembersService service;

    private Tenant tenant;
    private User currentUser;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Família Silva");

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        currentUser.setName("Admin");
        currentUser.setEmail("admin@silva.com");
        currentUser.setRole(UserRole.ADMIN);
        currentUser.setTenant(tenant);
    }

    @Test
    @DisplayName("list retorna membros ordenados por nome do tenant")
    void list_returnsMembersForTenant() {
        User member = new User();
        member.setId(UUID.randomUUID());
        member.setName("Maria");
        member.setEmail("maria@silva.com");
        member.setRole(UserRole.USER);
        member.setTenant(tenant);

        when(userRepository.findAllByTenantIdOrderByNameAsc(tenant.getId()))
                .thenReturn(List.of(currentUser, member));

        List<MemberDTO> result = service.list(currentUser);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Admin");
        assertThat(result.get(1).name()).isEqualTo("Maria");
        assertThat(result.get(1).role()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("list consulta apenas pelo tenant do usuário autenticado")
    void list_queriesByTenantId() {
        when(userRepository.findAllByTenantIdOrderByNameAsc(tenant.getId()))
                .thenReturn(List.of());

        service.list(currentUser);

        verify(userRepository).findAllByTenantIdOrderByNameAsc(tenant.getId());
    }
}
