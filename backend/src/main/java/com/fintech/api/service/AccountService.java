package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.account.CreditCardDetails;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.account.*;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;

    @Transactional(readOnly = true)
    public List<AccountResponseDTO> findAll(User user) {
        return accountRepository.findAllByTenantAndActiveTrueOrderByName(user.getTenant())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponseDTO findById(UUID id, User user) {
        Account account = findAccount(id, user);
        return toResponse(account);
    }

    @Transactional
    public AccountResponseDTO create(AccountCreateDTO dto, User user) {
        boolean liquidDefault = dto.type() == AccountType.CHECKING || dto.type() == AccountType.CASH;

        Account account = Account.builder()
                .name(dto.name())
                .type(dto.type())
                .color(dto.color())
                .icon(dto.icon())
                .countInLiquidBalance(dto.countInLiquidBalance() != null ? dto.countInLiquidBalance() : liquidDefault)
                .countInNetWorth(dto.countInNetWorth() != null ? dto.countInNetWorth() : true)
                .tenant(user.getTenant())
                .createdBy(user)
                .build();

        account = accountRepository.save(account);

        if (dto.type() == AccountType.CREDIT_CARD && dto.creditCardDetails() != null) {
            saveCreditCardDetails(account, dto.creditCardDetails());
        }

        return toResponse(account);
    }

    @Transactional
    public AccountResponseDTO update(UUID id, AccountUpdateDTO dto, User user) {
        Account account = findAccount(id, user);

        if (dto.name() != null)                 account.setName(dto.name());
        if (dto.color() != null)                account.setColor(dto.color());
        if (dto.icon() != null)                 account.setIcon(dto.icon());
        if (dto.countInLiquidBalance() != null) account.setCountInLiquidBalance(dto.countInLiquidBalance());
        if (dto.countInNetWorth() != null)      account.setCountInNetWorth(dto.countInNetWorth());

        return toResponse(account);
    }

    @Transactional
    public void archive(UUID id, User user) {
        Account account = findAccount(id, user);
        account.setActive(false);
    }

    private Account findAccount(UUID id, User user) {
        return accountRepository.findByIdAndTenant(id, user.getTenant())
                .orElseThrow(() -> new EntityNotFoundException("Conta não encontrada."));
    }

    private void saveCreditCardDetails(Account account, CreditCardDetailsDTO dto) {
        CreditCardDetails details = CreditCardDetails.builder()
                .account(account)
                .brand(dto.brand())
                .lastFourDigits(dto.lastFourDigits())
                .limitAmount(dto.limitAmount())
                .closingDay(dto.closingDay())
                .dueDay(dto.dueDay())
                .build();
        creditCardDetailsRepository.save(details);
    }

    private AccountResponseDTO toResponse(Account account) {
        BigDecimal balance = accountRepository.calculateBalance(
                account, TransactionType.INCOME, TransactionStatus.CANCELLED);

        CreditCardDetailsResponseDTO detailsDto = null;
        if (account.getType() == AccountType.CREDIT_CARD) {
            detailsDto = creditCardDetailsRepository.findByAccount(account)
                    .map(d -> new CreditCardDetailsResponseDTO(
                            d.getBrand(), d.getLastFourDigits(), d.getLimitAmount(),
                            d.getClosingDay(), d.getDueDay()))
                    .orElse(null);
        }

        return new AccountResponseDTO(
                account.getId(), account.getName(), account.getType(),
                account.getColor(), account.getIcon(),
                account.isCountInLiquidBalance(), account.isCountInNetWorth(),
                account.isActive(), balance, detailsDto);
    }
}
