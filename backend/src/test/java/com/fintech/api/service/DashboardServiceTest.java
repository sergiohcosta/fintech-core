package com.fintech.api.service;

import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.dashboard.DashboardSummaryDTO;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static com.fintech.api.domain.enums.TransactionType.EXPENSE;
import static com.fintech.api.domain.enums.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock TransactionRepository transactionRepository;
    @InjectMocks DashboardService service;

    private final YearMonth period = YearMonth.of(2026, 6);

    @Test
    @DisplayName("Mês sem movimentação: transactionCount=0 e totalAccountBalance reflete posição acumulada")
    void emptySummaryPreservesAccountBalance() {
        User user = buildUser();
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(INCOME), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(EXPENSE), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByTenantAndPeriod(any(), any(), any(), any()))
                .thenReturn(0L);
        when(transactionRepository.sumNetLiquidBalanceByTenant(any(), any(), any()))
                .thenReturn(new BigDecimal("3400.00"));

        DashboardSummaryDTO result = service.getSummary(period, user);

        assertThat(result.transactionCount()).isZero();
        assertThat(result.totalIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalExpense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalAccountBalance()).isEqualByComparingTo(new BigDecimal("3400.00"));
    }

    @Test
    @DisplayName("Mês com movimentação: balance = income - expense; posição acumulada independe do período")
    void summaryWithTransactionsComputesBalanceCorrectly() {
        User user = buildUser();
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(INCOME), any(), any(), any()))
                .thenReturn(new BigDecimal("5000.00"));
        when(transactionRepository.sumByTenantAndTypeAndPeriod(any(), eq(EXPENSE), any(), any(), any()))
                .thenReturn(new BigDecimal("1600.00"));
        when(transactionRepository.countByTenantAndPeriod(any(), any(), any(), any()))
                .thenReturn(5L);
        when(transactionRepository.sumNetLiquidBalanceByTenant(any(), any(), any()))
                .thenReturn(new BigDecimal("3400.00"));

        DashboardSummaryDTO result = service.getSummary(period, user);

        assertThat(result.transactionCount()).isEqualTo(5);
        assertThat(result.totalIncome()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(result.totalExpense()).isEqualByComparingTo(new BigDecimal("1600.00"));
        assertThat(result.balance()).isEqualByComparingTo(new BigDecimal("3400.00"));
        assertThat(result.totalAccountBalance()).isEqualByComparingTo(new BigDecimal("3400.00"));
    }

    private User buildUser() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        User user = new User();
        user.setTenant(tenant);
        return user;
    }
}
