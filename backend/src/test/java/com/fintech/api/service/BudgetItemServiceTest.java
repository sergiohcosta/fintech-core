package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetItemServiceTest {

    @Mock BudgetItemRepository repository;
    @Mock TransactionRepository transactionRepository;

    @InjectMocks BudgetItemService service;

    @Test
    @DisplayName("update() em item INSTALLMENT lança IllegalStateException")
    void update_itemInstallment_lançaException() {
        BudgetItem item = itemWith(BudgetItemSource.INSTALLMENT);

        assertThatThrownBy(() -> service.update(item,
            new BudgetItemUpdateRequest("desc", BigDecimal.TEN, LocalDate.now(), null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("parcela");
    }

    @Test
    @DisplayName("update() em item MANUAL atualiza campos e salva")
    void update_itemManual_atualizaCampos() {
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var updated = service.update(item,
            new BudgetItemUpdateRequest("Nova desc", new BigDecimal("500.00"),
                LocalDate.of(2026, 6, 15), null, null));

        assertThat(updated.getDescription()).isEqualTo("Nova desc");
        assertThat(updated.getAmount()).isEqualByComparingTo("500.00");
        assertThat(updated.getExpectedDate()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    @DisplayName("link() muda status para REALIZED e preenche transaction")
    void link_transacaoValida_mudaParaRealized() {
        BudgetCycle cycle = new BudgetCycle();
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        item.setCycle(cycle);
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());

        when(transactionRepository.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(repository.findByTransactionAndCycleNot(tx, cycle)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetItem result = service.link(item, tx.getId());

        assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.REALIZED);
        assertThat(result.getTransaction()).isEqualTo(tx);
    }

    @Test
    @DisplayName("link() lança IllegalStateException se transação já vinculada a outro item")
    void link_transacaoJaVinculada_lançaException() {
        BudgetCycle cycle = new BudgetCycle();
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        item.setCycle(cycle);
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());

        when(transactionRepository.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(repository.findByTransactionAndCycleNot(tx, cycle))
            .thenReturn(Optional.of(new BudgetItem()));

        assertThatThrownBy(() -> service.link(item, tx.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("vinculada");
    }

    @Test
    @DisplayName("unlink() volta status para PENDING e limpa transaction")
    void unlink_voltaParaPending() {
        BudgetItem item = itemWith(BudgetItemSource.MANUAL);
        item.setStatus(BudgetItemStatus.REALIZED);
        item.setTransaction(new Transaction());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BudgetItem result = service.unlink(item);

        assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.PENDING);
        assertThat(result.getTransaction()).isNull();
    }

    private BudgetItem itemWith(BudgetItemSource source) {
        return BudgetItem.builder()
            .id(UUID.randomUUID())
            .description("Item teste")
            .amount(BigDecimal.TEN)
            .type(TransactionType.EXPENSE)
            .expectedDate(LocalDate.now())
            .source(source)
            .status(BudgetItemStatus.PENDING)
            .build();
    }
}
