package com.fintech.api.service;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.dashboard.DashboardSummaryDTO;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryDTO getSummary(YearMonth period, User user) {
        var tenant = user.getTenant();
        var start = period.atDay(1);
        var end = period.atEndOfMonth();

        BigDecimal income = transactionRepository.sumByTenantAndTypeAndPeriod(
                tenant, TransactionType.INCOME, TransactionStatus.CANCELLED, start, end);
        BigDecimal expense = transactionRepository.sumByTenantAndTypeAndPeriod(
                tenant, TransactionType.EXPENSE, TransactionStatus.CANCELLED, start, end);
        long count = transactionRepository.countByTenantAndPeriod(
                tenant, TransactionStatus.CANCELLED, start, end);
        BigDecimal totalAccountBalance = transactionRepository.sumNetLiquidBalanceByTenant(
                tenant, TransactionType.INCOME, TransactionStatus.PAID);

        return DashboardSummaryDTO.of(period, income, expense, count, totalAccountBalance);
    }
}
