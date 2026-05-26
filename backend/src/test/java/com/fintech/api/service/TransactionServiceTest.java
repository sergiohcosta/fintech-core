package com.fintech.api.service;

import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transaction.TransactionRequestDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.dto.transaction.TransactionUpdateDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.CreditCardRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository repository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CreditCardRepository creditCardRepository;

    @InjectMocks
    private TransactionService service;

    @Test
    @DisplayName("Should create a single transaction when installments is 1 or null")
    void shouldCreateSingleTransaction() {
        // Arrange
        User user = new User();
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Test Transaction",
                new BigDecimal("100.00"),
                LocalDate.now(),
                null, // Type
                null, // Status
                1, // TotalInstallments
                null, // CategoryId
                null // CreditCardId
        );

        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<TransactionResponseDTO> result = service.create(dto, user);

        // Assert
        assertEquals(1, result.size());
        assertEquals(new BigDecimal("100.00"), result.getFirst().amount());
        verify(repository, times(1)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Should split amount correctly into installments")
    void shouldSplitAmountIntoInstallments() {
        // Arrange
        User user = new User();
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Installment Transaction",
                new BigDecimal("100.00"),
                LocalDate.now(),
                null, // Type
                null, // Status
                2, // TotalInstallments
                null, // CategoryId
                null // CreditCardId
        );

        when(repository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<TransactionResponseDTO> result = service.create(dto, user);

        // Assert
        assertEquals(2, result.size());
        // 100 / 2 = 50
        assertEquals(new BigDecimal("50.00"), result.get(0).amount());
        assertEquals(new BigDecimal("50.00"), result.get(1).amount());

        // Check dates
        assertEquals(dto.date(), result.get(0).date());
        assertEquals(dto.date().plusMonths(1), result.get(1).date());

        verify(repository, times(2)).save(any(Transaction.class));
    }

    @Test
    @DisplayName("Should throw exception if Category not found")
    void shouldThrowExceptionIfCategoryNotFound() {
        // Arrange
        User user = new User();
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());
        UUID categoryId = UUID.randomUUID();

        TransactionRequestDTO dto = new TransactionRequestDTO(
                "Test", BigDecimal.TEN, LocalDate.now(), null, null, 1, categoryId, null);

        when(categoryRepository.findByIdAndTenantId(categoryId, user.getTenant().getId()))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(com.fintech.api.exception.EntityNotFoundException.class, () -> service.create(dto, user));
    }

    @Test
    @DisplayName("Should update description when update is called with valid id and tenant")
    void shouldUpdateTransactionDescription() {
        User user = buildUser();
        UUID id = UUID.randomUUID();

        Transaction existing = Transaction.builder()
                .description("Original")
                .amount(new BigDecimal("100.00"))
                .date(LocalDate.now())
                .type(TransactionType.EXPENSE)
                .tenant(user.getTenant())
                .user(user)
                .build();

        TransactionUpdateDTO dto = new TransactionUpdateDTO("Atualizado", null, null, null, null, null, null);

        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.of(existing));

        TransactionResponseDTO result = service.update(id, dto, user);

        assertEquals("Atualizado", result.description());
        // O save() não é chamado explicitamente — o Hibernate faz dirty checking
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw EntityNotFoundException when updating transaction of another tenant")
    void shouldThrowWhenUpdatingTransactionOfAnotherTenant() {
        User user = buildUser();
        UUID id = UUID.randomUUID();

        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.empty());

        TransactionUpdateDTO dto = new TransactionUpdateDTO("X", null, null, null, null, null, null);

        assertThrows(EntityNotFoundException.class, () -> service.update(id, dto, user));
    }

    @Test
    @DisplayName("Should delete transaction when id and tenant match")
    void shouldDeleteTransaction() {
        User user = buildUser();
        UUID id = UUID.randomUUID();

        Transaction existing = Transaction.builder()
                .description("Para excluir")
                .amount(BigDecimal.TEN)
                .date(LocalDate.now())
                .type(TransactionType.EXPENSE)
                .tenant(user.getTenant())
                .user(user)
                .build();

        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.of(existing));

        service.delete(id, user);

        verify(repository, times(1)).delete(existing);
    }

    @Test
    @DisplayName("Should throw EntityNotFoundException when deleting transaction of another tenant")
    void shouldThrowWhenDeletingTransactionOfAnotherTenant() {
        User user = buildUser();
        UUID id = UUID.randomUUID();

        when(repository.findByIdAndTenant(id, user.getTenant())).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.delete(id, user));
    }

    private User buildUser() {
        User user = new User();
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        user.setTenant(tenant);
        return user;
    }
}
