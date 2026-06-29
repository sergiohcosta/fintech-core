package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.RecurrenceStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.recurrence.RecurrenceException;
import com.fintech.api.domain.recurrence.RecurrenceRule;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.recurrence.ConfirmOccurrenceDTO;
import com.fintech.api.dto.transaction.TransactionResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.RecurrenceExceptionRepository;
import com.fintech.api.repository.RecurrenceRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurrenceRuleServiceTest {

    @Mock RecurrenceRuleRepository repository;
    @Mock AccountRepository accountRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock RecurrenceExceptionRepository exceptionRepository;
    @Mock TransactionService transactionService;
    @InjectMocks RecurrenceRuleService service;

    private User user;
    private final UUID ruleId = UUID.randomUUID();
    private final LocalDate occ = LocalDate.of(2026, 8, 10);

    @BeforeEach
    void setup() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        user = new User();
        user.setTenant(tenant);
    }

    private RecurrenceRule rule() {
        Account account = Account.builder().id(UUID.randomUUID()).name("Conta Corrente").build();
        return RecurrenceRule.builder().id(ruleId).tenant(user.getTenant())
                .description("Netflix").baseAmount(new BigDecimal("100.00"))
                .type(TransactionType.EXPENSE).rrule("FREQ=MONTHLY;BYMONTHDAY=10")
                .startDate(LocalDate.of(2026, 1, 10)).account(account).build();
    }

    @Test
    void confirmDelegaParaMaterializacaoQuandoNaoMaterializada() {
        RecurrenceRule rule = rule();
        TransactionResponseDTO dto = mock(TransactionResponseDTO.class);
        when(repository.findByIdAndTenant(ruleId, user.getTenant())).thenReturn(Optional.of(rule));
        when(transactionService.existsMaterializedOccurrence(ruleId, occ)).thenReturn(false);
        when(transactionService.materializeFromRule(rule, occ, new BigDecimal("150.00"), null, user))
                .thenReturn(dto);

        var result = service.confirmOccurrence(ruleId, occ,
                new ConfirmOccurrenceDTO(new BigDecimal("150.00"), null), user);

        assertThat(result).isSameAs(dto);
    }

    @Test
    void confirmRejeitaOcorrenciaJaMaterializada() {
        when(repository.findByIdAndTenant(ruleId, user.getTenant())).thenReturn(Optional.of(rule()));
        when(transactionService.existsMaterializedOccurrence(ruleId, occ)).thenReturn(true);

        assertThatThrownBy(() -> service.confirmOccurrence(ruleId, occ, null, user))
                .isInstanceOf(BusinessConflictException.class);
        verify(transactionService, never()).materializeFromRule(any(), any(), any(), any(), any());
    }

    @Test
    void skipGravaExdateQuandoInexistente() {
        when(repository.findByIdAndTenant(ruleId, user.getTenant())).thenReturn(Optional.of(rule()));
        when(exceptionRepository.existsByRuleIdAndOccurrenceDate(ruleId, occ)).thenReturn(false);

        service.skipOccurrence(ruleId, occ, user);

        verify(exceptionRepository).save(any(RecurrenceException.class));
    }

    @Test
    void skipEhIdempotente() {
        when(repository.findByIdAndTenant(ruleId, user.getTenant())).thenReturn(Optional.of(rule()));
        when(exceptionRepository.existsByRuleIdAndOccurrenceDate(ruleId, occ)).thenReturn(true);

        service.skipOccurrence(ruleId, occ, user);

        verify(exceptionRepository, never()).save(any());
    }

    @Test
    void regraDeOutroTenantNaoEncontrada() {
        when(repository.findByIdAndTenant(ruleId, user.getTenant())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.skipOccurrence(ruleId, occ, user))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("reactivate() em regra CANCELLED → retorna DTO com status ACTIVE")
    void reactivate_regraActiva_retornaActive() {
        RecurrenceRule cancelledRule = rule();
        cancelledRule.setStatus(RecurrenceStatus.CANCELLED);

        when(repository.findByIdAndTenant(ruleId, user.getTenant()))
                .thenReturn(Optional.of(cancelledRule));
        when(repository.save(cancelledRule)).thenReturn(cancelledRule);

        com.fintech.api.dto.recurrence.RecurrenceRuleResponseDTO result = service.reactivate(ruleId, user);

        assertThat(result.status()).isEqualTo(RecurrenceStatus.ACTIVE);
        verify(repository).save(cancelledRule);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("reactivate() em regra ACTIVE → lança IllegalStateException")
    void reactivate_regraJaActive_lancaException() {
        RecurrenceRule activeRule = rule(); // status ACTIVE por padrão (@Builder.Default)

        when(repository.findByIdAndTenant(ruleId, user.getTenant()))
                .thenReturn(Optional.of(activeRule));

        assertThatThrownBy(() -> service.reactivate(ruleId, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A regra já está ativa.");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("reactivate() com ID inexistente → EntityNotFoundException")
    void reactivate_regraInexistente_lancaNotFound() {
        when(repository.findByIdAndTenant(ruleId, user.getTenant()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactivate(ruleId, user))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
