package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.CardBrand;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.*;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock CreditCardDetailsRepository creditCardDetailsRepository;
    @InjectMocks AccountService service;

    @Test
    @DisplayName("Conta corrente criada com countInLiquidBalance=true por padrão")
    void checkingDefaultsToLiquid() {
        User user = buildUser();
        AccountCreateDTO dto = new AccountCreateDTO(
                "Bradesco", AccountType.CHECKING, "#FF0000", null, null, null, null);
        Account saved = Account.builder().id(UUID.randomUUID()).name("Bradesco")
                .type(AccountType.CHECKING).countInLiquidBalance(true).countInNetWorth(true)
                .active(true).tenant(user.getTenant()).build();

        when(accountRepository.save(any())).thenReturn(saved);
        when(accountRepository.calculateBalance(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        AccountResponseDTO result = service.create(dto, user);

        assertThat(result.countInLiquidBalance()).isTrue();
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Conta de investimento criada com countInLiquidBalance=false por padrão")
    void investmentDefaultsToNotLiquid() {
        User user = buildUser();
        AccountCreateDTO dto = new AccountCreateDTO(
                "Tesouro Direto", AccountType.INVESTMENT, null, null, null, null, null);
        Account saved = Account.builder().id(UUID.randomUUID()).name("Tesouro Direto")
                .type(AccountType.INVESTMENT).countInLiquidBalance(false).countInNetWorth(true)
                .active(true).tenant(user.getTenant()).build();

        when(accountRepository.save(any())).thenReturn(saved);
        when(accountRepository.calculateBalance(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        AccountResponseDTO result = service.create(dto, user);

        assertThat(result.countInLiquidBalance()).isFalse();
    }

    @Test
    @DisplayName("Cartão de crédito persiste credit_card_details")
    void creditCardPersistsDetails() {
        User user = buildUser();
        CreditCardDetailsDTO details = new CreditCardDetailsDTO(
                CardBrand.VISA, "1234", new BigDecimal("5000"), 10, 15);
        AccountCreateDTO dto = new AccountCreateDTO(
                "Nubank", AccountType.CREDIT_CARD, "#8A05BE", null, null, null, details);
        Account saved = Account.builder().id(UUID.randomUUID()).name("Nubank")
                .type(AccountType.CREDIT_CARD).countInLiquidBalance(false).countInNetWorth(true)
                .active(true).tenant(user.getTenant()).build();

        when(accountRepository.save(any())).thenReturn(saved);
        when(accountRepository.calculateBalance(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(creditCardDetailsRepository.findByAccount(any())).thenReturn(Optional.empty());

        service.create(dto, user);

        ArgumentCaptor<CreditCardDetails> captor = ArgumentCaptor.forClass(CreditCardDetails.class);
        verify(creditCardDetailsRepository).save(captor.capture());
        assertThat(captor.getValue().getBrand()).isEqualTo(CardBrand.VISA);
        assertThat(captor.getValue().getClosingDay()).isEqualTo(10);
    }

    @Test
    @DisplayName("findById lança EntityNotFoundException para conta de outro tenant")
    void findByIdThrowsForOtherTenant() {
        User user = buildUser();
        when(accountRepository.findByIdAndTenant(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), user))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Conta não encontrada");
    }

    @Test
    @DisplayName("archive define active=false")
    void archiveSetsActiveFalse() {
        User user = buildUser();
        Account account = Account.builder().id(UUID.randomUUID()).name("Corrente")
                .type(AccountType.CHECKING).active(true).tenant(user.getTenant()).build();
        when(accountRepository.findByIdAndTenant(account.getId(), user.getTenant()))
                .thenReturn(Optional.of(account));

        service.archive(account.getId(), user);

        assertThat(account.isActive()).isFalse();
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        return user;
    }
}
