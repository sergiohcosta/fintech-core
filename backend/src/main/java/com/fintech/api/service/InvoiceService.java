package com.fintech.api.service;

import com.fintech.api.domain.account.Account;
import com.fintech.api.domain.enums.AccountType;
import com.fintech.api.domain.enums.InvoiceStatus;
import com.fintech.api.domain.enums.TransactionStatus;
import com.fintech.api.domain.enums.TransactionType;
import com.fintech.api.domain.invoice.Invoice;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.transaction.Transaction;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.invoice.InvoiceResponseDTO;
import com.fintech.api.exception.BusinessException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.AccountRepository;
import com.fintech.api.repository.CreditCardDetailsRepository;
import com.fintech.api.repository.InvoiceRepository;
import com.fintech.api.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository repository;
    private final CreditCardDetailsRepository creditCardDetailsRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    // Self-injection via proxy para que @Transactional(REQUIRES_NEW) em createNewInvoice
    // seja honrado pelo Spring AOP — chamadas em `this` ignoram o proxy e a anotação.
    @Lazy
    @Autowired
    private InvoiceService self;

    @Transactional
    public Invoice getOrCreate(Account account, int referenceYear, int referenceMonth) {
        // #244: lock pessimista (SELECT ... FOR UPDATE) serializa contra close/pay concorrentes.
        // Sem ele, um close() entre esta leitura e o save() da parcela filha muda o status
        // debaixo dos nossos pés e a parcela fica anexada a uma fatura CLOSED/PAID — exatamente
        // a janela de corrida que o issue #244 documenta. Faturas diferentes não disputam o
        // mesmo lock, então não introduzimos contenção entre faturas.
        // Importante: este método NÃO revalida o status ao devolver a fatura existente — isso
        // é responsabilidade do caller (TransactionService.create/materializeFromRule fazem
        // findExistingForUpdate ANTES e descartam/recusam se != OPEN; o caminho de importação
        // com âncora skipClosedInvoices=false usa getOrCreate direto e aceita fatura
        // CLOSED/PAID por design). A revalidação aqui quebraria o import (que precisa anexar
        // parcelas à fatura-alvo do documento mesmo que ela já esteja fechada) e duplicaria
        // o check que o caller já fez no caminho manual/recorrência.
        Optional<Invoice> existing = repository
                .findByAccountAndReferenceYearAndReferenceMonthForUpdate(
                        account, referenceYear, referenceMonth);
        if (existing.isPresent()) return existing.get();

        try {
            return self.createNewInvoice(account, referenceYear, referenceMonth);
        } catch (DataIntegrityViolationException e) {
            // Race condition: outra thread ganhou a corrida — retorna a fatura vencedora.
            // O retry aqui cobre o caso de duas inserções simultâneas da MESMA fatura (UNIQUE
            // constraint na V9); com o lock acima, duas getOrCreate simultâneas serializam e
            // a vencedora cria; a perdedora bloqueia no SELECT FOR UPDATE, depois da vencedora
            // commitar lê a fatura já criada via existing.isPresent() e segue. O catch
            // DataIntegrityViolationException é a rede de segurança para a janela minúscula
            // entre a leitura do locked finder (que retorna empty porque a linha não existe)
            // e o INSERT da fatura (que outra thread pode ter ganhado).
            return repository.findByAccountAndReferenceYearAndReferenceMonth(
                            account, referenceYear, referenceMonth)
                    .orElseThrow(() -> new IllegalStateException(
                            "Fatura não encontrada após conflito de inserção", e));
        }
    }

    // Transação separada (REQUIRES_NEW) para que um conflito de chave única não contamine
    // a transação externa — permite o catch em getOrCreate fazer o retry com findBy.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Invoice createNewInvoice(Account account, int referenceYear, int referenceMonth) {
        var details = creditCardDetailsRepository.findByAccount(account)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Detalhes do cartão não encontrados para a conta."));
        InvoiceSchedule schedule = scheduleFor(
                referenceYear, referenceMonth, details.getClosingDay(), details.getDueDay());

        return repository.save(Invoice.builder()
                .account(account)
                .tenant(account.getTenant())
                .referenceYear(referenceYear)
                .referenceMonth(referenceMonth)
                .closingDate(schedule.closingDate())
                .dueDate(schedule.dueDate())
                .status(InvoiceStatus.OPEN)
                .build());
    }

    /**
     * Par (closingDate, dueDate) de uma fatura de referência, extraído de {@code createNewInvoice}
     * para reuso no preview de parcelas (D5) — o preview precisa exibir o mesmo vencimento que a
     * fatura teria, sem materializar nada no banco.
     */
    public record InvoiceSchedule(LocalDate closingDate, LocalDate dueDate) {}

    static InvoiceSchedule scheduleFor(int referenceYear, int referenceMonth, int closingDay, int dueDay) {
        // A fatura de referenceMonth fecha no mês seguinte (ex: fatura de junho fecha em 02/julho).
        // #137: capar o dia ao tamanho do mês evita DateTimeException (ex: closingDay=31 em
        // fevereiro). A comparação dueDay >= closingDay abaixo permanece sobre os dias
        // CONFIGURADOS, não os capados — capar antes mudaria o mês da fatura em fevereiro.
        LocalDate closingDate = atDayCapped(
                LocalDate.of(referenceYear, referenceMonth, 1).plusMonths(1), closingDay);
        LocalDate dueDate = dueDay >= closingDay
                ? atDayCapped(closingDate, dueDay)
                : atDayCapped(closingDate.plusMonths(1), dueDay);
        return new InvoiceSchedule(closingDate, dueDate);
    }

    /**
     * Leitura sem materialização: devolve a fatura se existir, sem criá-la (D2). Usado pelo
     * descarte de parcelas em fatura fechada e pelo preview — ambos precisam saber o status
     * atual sem o efeito colateral de {@code getOrCreate}.
     */
    @Transactional(readOnly = true)
    public Optional<Invoice> findExisting(Account account, int referenceYear, int referenceMonth) {
        return repository.findByAccountAndReferenceYearAndReferenceMonth(account, referenceYear, referenceMonth);
    }

    /**
     * #244: variante com lock pessimista — mesma semântica de {@link #findExisting}, mas
     * dentro de uma transação que disputa o lock de linha da fatura com {@link #close},
     * {@link #pay} e outros callers de {@link #getOrCreate}. Usada por
     * {@code TransactionService.create}/{@code materializeFromRule} antes de gravar a parcela
     * filha — sem o lock, o status lido aqui pode divergir do status que {@code getOrCreate}
     * (também sob lock) vai ver, e a parcela acaba anexada a uma fatura que fechou entre
     * as duas leituras. O lock serializa as duas operações na MESMA transação lógica.
     */
    @Transactional
    public Optional<Invoice> findExistingForUpdate(Account account, int referenceYear, int referenceMonth) {
        return repository.findByAccountAndReferenceYearAndReferenceMonthForUpdate(
                account, referenceYear, referenceMonth);
    }

    // #137: "dia N ou o último dia do mês, o que vier primeiro" — mesma semântica do
    // BYMONTHDAY=-1 do motor de recorrência. Evita withDayOfMonth(31) estourar em meses curtos.
    private static LocalDate atDayCapped(LocalDate base, int day) {
        return base.withDayOfMonth(Math.min(day, base.lengthOfMonth()));
    }

    @Transactional(readOnly = true)
    public Invoice findByIdAndTenant(UUID id, Tenant tenant) {
        return loadByIdAndTenant(id, tenant);
    }

    // Lookup interno SEM @Transactional, usado pelos métodos deste service (getDTO/close/pay),
    // que já abrem transação própria — o find participa dela. Evita o self-invocation de um
    // método @Transactional via 'this', que passaria por fora do proxy do Spring e ignoraria a
    // anotação (regra S6809). O finder público acima mantém o @Transactional(readOnly=true) para
    // quando é ponto de entrada via proxy (ex.: chamado por TransactionService).
    private Invoice loadByIdAndTenant(UUID id, Tenant tenant) {
        // Comparação por ID evita Lombok canEqual() em proxy detached do SecurityContext
        return repository.findById(id)
                .filter(inv -> inv.getAccount().getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new EntityNotFoundException("Fatura não encontrada."));
    }

    // #244: variante com lock pessimista para close/pay. Adquire SELECT ... FOR UPDATE na
    // fatura-alvo — serializa contra getOrCreate concorrente (que adquire o mesmo lock na
    // MESMA linha). Sem o lock, duas pay() simultâneas leem CLOSED, ambas passam pelo guard
    // `if (status != CLOSED)` antes do markAsPaidIfClosed — só o markAsPaidIfClosed atômico
    // barra a duplicação (rede final do #139). Com o lock, o close também serializa, e a
    // janela "leitura status → gravação parcela" do #244 fica fechada ponta-a-ponta.
    private Invoice loadByIdAndTenantForUpdate(UUID id, Tenant tenant) {
        return repository.findByIdForUpdate(id)
                .filter(inv -> inv.getAccount().getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new EntityNotFoundException("Fatura não encontrada."));
    }

    // Retorna DTO com total e contagem — tudo dentro da transação para evitar LazyInitializationException
    @Transactional(readOnly = true)
    public InvoiceResponseDTO getDTO(UUID id, Tenant tenant) {
        return buildDTO(loadByIdAndTenant(id, tenant));
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponseDTO> listDTOs(Account account) {
        return repository.findByAccountWithTotals(account)
                .stream()
                .map(row -> InvoiceResponseDTO.fromEntity(
                        (Invoice) row[0],
                        row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO,
                        (Long) row[2]))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Invoice> findByAccount(Account account) {
        return repository.findByAccountOrderByReferenceYearDescReferenceMonthDesc(account);
    }

    @Transactional
    public InvoiceResponseDTO close(UUID id, Tenant tenant) {
        // #244: lock pessimista antes de ler o status — serializa contra getOrCreate concorrente
        // (que disputa o mesmo lock). Sem o lock, a checagem `status != OPEN` poderia ver OPEN
        // enquanto um getOrCreate simultâneo, no momento de gravar a parcela, vê CLOSED.
        Invoice invoice = loadByIdAndTenantForUpdate(id, tenant);
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new IllegalStateException(
                    "Só é possível fechar faturas com status OPEN. Status atual: " + invoice.getStatus());
        }
        invoice.setStatus(InvoiceStatus.CLOSED);
        log.info("Fatura fechada [invoiceId={} referencia={}/{} tenantId={}]",
                id, invoice.getReferenceMonth(), invoice.getReferenceYear(), tenant.getId());
        return buildDTO(repository.save(invoice));
    }

    @Transactional
    public InvoiceResponseDTO pay(UUID id, Tenant tenant, User user, UUID sourceAccountId, LocalDate paymentDate) {
        // #244: lock pessimista aqui também — serializa contra getOrCreate concorrente. O
        // markAsPaidIfClosed abaixo (UPDATE atômico, herança do #139) já é a rede final contra
        // dois pay() simultâneos, mas o lock fecha a janela entre a leitura do status e o
        // markAsPaidIfClosed para qualquer getOrCreate que esteja no meio do caminho.
        Invoice invoice = loadByIdAndTenantForUpdate(id, tenant);
        if (invoice.getStatus() != InvoiceStatus.CLOSED) {
            throw new IllegalStateException(
                    "Só é possível pagar faturas com status CLOSED. Status atual: " + invoice.getStatus());
        }

        Account sourceAccount = accountRepository.findByIdAndTenant(sourceAccountId, tenant)
                .orElseThrow(() -> new EntityNotFoundException("Conta de origem não encontrada."));

        if (sourceAccount.getType() == AccountType.CREDIT_CARD) {
            throw new IllegalStateException(
                    "Não é possível pagar uma fatura com outra conta de cartão de crédito.");
        }

        // #199: ausência de paymentDate vira hoje; data futura é rejeitada aqui (400) porque o
        // pagamento nasce PAID e o totalAccountBalance não filtra período — uma data futura
        // rebaixaria hoje um caixa que ainda não saiu.
        LocalDate effectiveDate = paymentDate != null ? paymentDate : LocalDate.now();
        if (effectiveDate.isAfter(LocalDate.now())) {
            throw new BusinessException("A data do pagamento não pode ser futura.");
        }

        // #139: claim atômico ANTES de qualquer efeito colateral. Sob concorrência os dois pay()
        // passam pelo fast-fail acima (ambos leem CLOSED), mas só um consegue mudar CLOSED→PAID.
        // O perdedor recebe 0 linhas afetadas e aborta aqui, sem criar o EXPENSE de pagamento.
        int claimed = repository.markAsPaidIfClosed(id, InvoiceStatus.CLOSED, InvoiceStatus.PAID);
        if (claimed == 0) {
            throw new IllegalStateException(
                    "Fatura não está mais no status CLOSED (pagamento concorrente já processado).");
        }

        BigDecimal total = transactionRepository.sumAmountByInvoice(invoice, TransactionStatus.CANCELLED);

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            Transaction payment = Transaction.builder()
                    .type(TransactionType.EXPENSE)
                    .status(TransactionStatus.PAID)
                    .amount(total)
                    .date(effectiveDate)
                    .description(String.format("Pagamento fatura %s %02d/%d",
                            invoice.getAccount().getName(),
                            invoice.getReferenceMonth(),
                            invoice.getReferenceYear()))
                    .account(sourceAccount)
                    .tenant(invoice.getTenant())
                    .user(user)
                    // #145: marca a transação como o pagamento desta fatura → dashboard a exclui
                    // dos agregados (a despesa já foi contada nas compras via invoice.dueDate).
                    .paidInvoice(invoice)
                    .build();
            transactionRepository.save(payment);
        }

        transactionRepository.updateStatusByInvoiceAndStatus(
                invoice, TransactionStatus.PENDING, TransactionStatus.PAID);

        invoice.setStatus(InvoiceStatus.PAID);
        log.info("Fatura paga [invoiceId={} referencia={}/{} tenantId={} sourceAccountId={} paymentDate={}]",
                id, invoice.getReferenceMonth(), invoice.getReferenceYear(),
                tenant.getId(), sourceAccountId, effectiveDate);
        return buildDTO(repository.save(invoice));
    }

    private InvoiceResponseDTO buildDTO(Invoice invoice) {
        var total = transactionRepository.sumAmountByInvoice(invoice, TransactionStatus.CANCELLED);
        var count = transactionRepository.countByInvoice(invoice);
        return InvoiceResponseDTO.fromEntity(invoice, total, count);
    }
}
