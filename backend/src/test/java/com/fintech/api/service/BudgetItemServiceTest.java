package com.fintech.api.service;

import com.fintech.api.domain.budget.BudgetCycle;
import com.fintech.api.domain.budget.BudgetItem;
import com.fintech.api.domain.enums.BudgetCycleStatus;
import com.fintech.api.domain.enums.BudgetItemSource;
import com.fintech.api.domain.enums.BudgetItemStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.budget.BudgetItemCreateRequest;
import com.fintech.api.dto.budget.BudgetItemUpdateRequest;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.BudgetItemRepository;
import com.fintech.api.repository.CategoryRepository;
import com.fintech.api.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import com.fintech.api.domain.recurrence.RecurrenceRule;
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
    @Mock CategoryRepository categoryRepository;
    @Mock AccountRepository accountRepository;

    @InjectMocks BudgetItemService service;

    private Tenant tenant;
    private User user;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Test Tenant");

        user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
    }

    // ---- helpers ----

    private BudgetCycle openCycle() {
        return BudgetCycle.builder()
            .id(UUID.randomUUID())
            .tenant(tenant)
            .startDate(LocalDate.of(2026, 6, 1))
            .endDate(LocalDate.of(2026, 6, 30))
            .openingBalance(BigDecimal.valueOf(1000))
            .status(BudgetCycleStatus.OPEN)
            .build();
    }

    private BudgetCycle closedCycle() {
        return BudgetCycle.builder()
            .id(UUID.randomUUID())
            .tenant(tenant)
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 31))
            .openingBalance(BigDecimal.valueOf(1000))
            .status(BudgetCycleStatus.CLOSED)
            .build();
    }

    private BudgetItem pendingItem(BudgetCycle cycle) {
        return BudgetItem.builder()
            .id(UUID.randomUUID())
            .cycle(cycle)
            .tenant(tenant)
            .description("Test")
            .amount(BigDecimal.valueOf(100))
            .type(TransactionType.EXPENSE)
            .expectedDate(cycle.getStartDate().plusDays(5))
            .source(BudgetItemSource.MANUAL)
            .status(BudgetItemStatus.PENDING)
            .build();
    }

    private BudgetItem realizedItem(BudgetCycle cycle) {
        BudgetItem item = pendingItem(cycle);
        item.setStatus(BudgetItemStatus.REALIZED);
        item.setTransaction(Transaction.builder().id(UUID.randomUUID()).build());
        return item;
    }

    private BudgetItem skippedItem(BudgetCycle cycle) {
        BudgetItem item = pendingItem(cycle);
        item.setStatus(BudgetItemStatus.SKIPPED);
        return item;
    }

    private Transaction expenseTransaction() {
        return Transaction.builder()
            .id(UUID.randomUUID())
            .description("Tx Test")
            .amount(BigDecimal.valueOf(150))
            .date(LocalDate.of(2026, 6, 10))
            .type(TransactionType.EXPENSE)
            .tenant(tenant)
            .user(user)
            .status(TransactionStatus.PAID)
            .build();
    }

    // ---- realize ----

    @Nested
    @DisplayName("realize()")
    class Realize {

        @Test
        @DisplayName("com transactionId existente: vincula e atualiza amount")
        void realize_withExistingTransaction_linksAndUpdatesAmount() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);
            Transaction tx = expenseTransaction();

            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant))
                .thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BudgetItem result = service.realize(item, tx.getId(), tenant, user);

            assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.REALIZED);
            assertThat(result.getTransaction()).isEqualTo(tx);
            assertThat(result.getAmount()).isEqualByComparingTo(tx.getAmount());
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("sem transactionId: cria nova transação")
        void realize_withoutTransactionId_createsNewTransaction() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);

            when(transactionRepository.save(any())).thenAnswer(inv -> {
                Transaction saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                return saved;
            });
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BudgetItem result = service.realize(item, null, tenant, user);

            assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.REALIZED);
            assertThat(result.getTransaction()).isNotNull();
            verify(transactionRepository).save(argThat(tx ->
                tx.getDescription().equals(item.getDescription()) &&
                tx.getAmount().compareTo(item.getAmount()) == 0 &&
                tx.getType() == item.getType() &&
                tx.getStatus() == TransactionStatus.PAID
            ));
        }

        @Test
        @DisplayName("transação de outro tenant: lança AccessDeniedException")
        void realize_transactionFromOtherTenant_throwsAccessDenied() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);
            UUID otherTenantTxId = UUID.randomUUID();

            when(transactionRepository.findByIdAndTenant(otherTenantTxId, tenant))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.realize(item, otherTenantTxId, tenant, user))
                .isInstanceOf(AccessDeniedException.class);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("transação já vinculada a outro item: lança IllegalStateException")
        void realize_transactionAlreadyLinked_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);
            Transaction tx = expenseTransaction();
            BudgetItem otherItem = pendingItem(cycle);
            otherItem.setId(UUID.randomUUID());

            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant))
                .thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.of(otherItem));

            assertThatThrownBy(() -> service.realize(item, tx.getId(), tenant, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vinculada");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("tipo incompatível: lança IllegalStateException")
        void realize_typeMismatch_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle); // EXPENSE
            Transaction tx = expenseTransaction();
            tx.setType(TransactionType.INCOME); // mismatch

            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant))
                .thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.realize(item, tx.getId(), tenant, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tipo");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("item não-PENDING: lança IllegalStateException")
        void realize_itemNotPending_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = realizedItem(cycle);

            assertThatThrownBy(() -> service.realize(item, UUID.randomUUID(), tenant, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pendentes");

            verify(transactionRepository, never()).findByIdAndTenant(any(), any());
        }

        @Test
        @DisplayName("ciclo CLOSED: lança IllegalStateException")
        void realize_cycleClosed_throwsIllegalState() {
            BudgetCycle cycle = closedCycle();
            BudgetItem item = pendingItem(cycle);

            assertThatThrownBy(() -> service.realize(item, UUID.randomUUID(), tenant, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fechado");

            verify(transactionRepository, never()).findByIdAndTenant(any(), any());
        }
    }

    // ---- link (#141: mesmos guards de realize) ----

    @Nested
    @DisplayName("link() — #141 guards unificados")
    class Link {

        @Test
        @DisplayName("transação já vinculada a outro item do MESMO ciclo: lança (era aceito)")
        void link_transactionAlreadyLinkedSameCycle_throws() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);
            Transaction tx = expenseTransaction();
            BudgetItem otherItem = pendingItem(cycle); // mesmo ciclo — o findByTransactionAndCycleNot antigo ignorava

            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant)).thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.of(otherItem));

            assertThatThrownBy(() -> service.link(item, tx.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vinculada");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("tipo da transação incompatível com o item: lança")
        void link_typeMismatch_throws() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle); // EXPENSE
            Transaction tx = expenseTransaction();
            tx.setType(TransactionType.INCOME);

            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant)).thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.link(item, tx.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tipo");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("ciclo CLOSED: lança (era aceito)")
        void link_cycleClosed_throws() {
            BudgetCycle cycle = closedCycle();
            BudgetItem item = pendingItem(cycle);

            assertThatThrownBy(() -> service.link(item, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fechado");
            verify(transactionRepository, never()).findByIdAndTenant(any(), any());
        }

        @Test
        @DisplayName("sincroniza o amount do item com o da transação (era ignorado)")
        void link_syncsAmount() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);   // amount 100
            Transaction tx = expenseTransaction();  // amount 150

            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant)).thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BudgetItem result = service.link(item, tx.getId());

            assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.REALIZED);
            assertThat(result.getAmount()).isEqualByComparingTo(tx.getAmount());
        }
    }

    // ---- linkRecurringOccurrence (#140) ----

    @Nested
    @DisplayName("linkRecurringOccurrence() — #140")
    class LinkRecurringOccurrence {

        @Test
        @DisplayName("com item RECURRING PENDENTE no ciclo aberto: vincula a transação")
        void links_whenMatchingItemExists() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle); // EXPENSE
            RecurrenceRule rule = RecurrenceRule.builder().id(UUID.randomUUID()).build();
            Transaction tx = expenseTransaction();
            LocalDate occ = LocalDate.of(2026, 6, 10);

            when(repository.findRecurringOccurrenceInOpenCycle(tenant, rule, occ)).thenReturn(Optional.of(item));
            when(transactionRepository.findByIdAndTenant(tx.getId(), tenant)).thenReturn(Optional.of(tx));
            when(repository.findByTransaction(tx)).thenReturn(Optional.empty());
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.linkRecurringOccurrence(tenant, rule, occ, tx.getId());

            assertThat(item.getStatus()).isEqualTo(BudgetItemStatus.REALIZED);
            assertThat(item.getTransaction()).isEqualTo(tx);
        }

        @Test
        @DisplayName("sem item correspondente (sem ciclo/projeção): não faz nada")
        void noop_whenNoMatch() {
            RecurrenceRule rule = RecurrenceRule.builder().id(UUID.randomUUID()).build();
            LocalDate occ = LocalDate.of(2026, 6, 10);
            when(repository.findRecurringOccurrenceInOpenCycle(tenant, rule, occ)).thenReturn(Optional.empty());

            service.linkRecurringOccurrence(tenant, rule, occ, UUID.randomUUID());

            verify(transactionRepository, never()).findByIdAndTenant(any(), any());
            verify(repository, never()).save(any());
        }
    }

    // ---- unrealize ----

    @Nested
    @DisplayName("unrealize()")
    class Unrealize {

        @Test
        @DisplayName("item realizado: remove vínculo e status → PENDING")
        void unrealize_realizedItem_revertsStatus() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = realizedItem(cycle);

            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BudgetItem result = service.unrealize(item);

            assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.PENDING);
            assertThat(result.getTransaction()).isNull();
        }

        @Test
        @DisplayName("ciclo CLOSED: lança IllegalStateException")
        void unrealize_cycleClosed_throwsIllegalState() {
            BudgetCycle cycle = closedCycle();
            BudgetItem item = realizedItem(cycle);

            assertThatThrownBy(() -> service.unrealize(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fechado");

            verify(repository, never()).save(any());
        }
    }

    // ---- skip ----

    @Nested
    @DisplayName("skip()")
    class Skip {

        @Test
        @DisplayName("item pendente: muda status para SKIPPED")
        void skip_pendingItem_changesStatus() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);

            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BudgetItem result = service.skip(item);

            assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.SKIPPED);
        }

        @Test
        @DisplayName("item realizado: lança IllegalStateException")
        void skip_realizedItem_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = realizedItem(cycle);

            assertThatThrownBy(() -> service.skip(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("realizados");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("ciclo CLOSED: lança IllegalStateException")
        void skip_cycleClosed_throwsIllegalState() {
            BudgetCycle cycle = closedCycle();
            BudgetItem item = pendingItem(cycle);

            assertThatThrownBy(() -> service.skip(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fechado");

            verify(repository, never()).save(any());
        }
    }

    // ---- unskip ----

    @Nested
    @DisplayName("unskip()")
    class Unskip {

        @Test
        @DisplayName("item pulado: reverte para PENDING")
        void unskip_skippedItem_revertsStatus() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = skippedItem(cycle);

            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            BudgetItem result = service.unskip(item);

            assertThat(result.getStatus()).isEqualTo(BudgetItemStatus.PENDING);
        }

        @Test
        @DisplayName("item não-SKIPPED: lança IllegalStateException")
        void unskip_notSkippedItem_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = pendingItem(cycle);

            assertThatThrownBy(() -> service.unskip(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pulados");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("ciclo CLOSED: lança IllegalStateException")
        void unskip_cycleClosed_throwsIllegalState() {
            BudgetCycle cycle = closedCycle();
            BudgetItem item = skippedItem(cycle);

            assertThatThrownBy(() -> service.unskip(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fechado");

            verify(repository, never()).save(any());
        }
    }

    // ---- update validations ----

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("item realizado: lança IllegalStateException")
        void update_realizedItem_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = realizedItem(cycle);

            var req = new BudgetItemUpdateRequest("desc", BigDecimal.TEN, LocalDate.of(2026, 6, 10), null, null);

            assertThatThrownBy(() -> service.update(item, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imutáveis");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("item pulado: lança IllegalStateException")
        void update_skippedItem_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = skippedItem(cycle);

            var req = new BudgetItemUpdateRequest("desc", BigDecimal.TEN, LocalDate.of(2026, 6, 10), null, null);

            assertThatThrownBy(() -> service.update(item, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revertido");

            verify(repository, never()).save(any());
        }
    }

    // ---- delete validations ----

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("item realizado: lança IllegalStateException")
        void delete_realizedItem_throwsIllegalState() {
            BudgetCycle cycle = openCycle();
            BudgetItem item = realizedItem(cycle);

            assertThatThrownBy(() -> service.delete(item))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imutáveis");

            verify(repository, never()).delete(any());
        }
    }

    // ---- create validations ----

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("ciclo CLOSED: lança IllegalStateException")
        void create_closedCycle_throwsIllegalState() {
            BudgetCycle cycle = closedCycle();
            var req = new BudgetItemCreateRequest(
                "Teste", BigDecimal.valueOf(200), TransactionType.EXPENSE,
                LocalDate.of(2026, 5, 15), null, null
            );

            assertThatThrownBy(() -> service.create(cycle, req, tenant, user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fechado");

            verify(repository, never()).save(any());
        }
    }
}
