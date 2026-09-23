package io.tradeflow.ledger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tradeflow.ledger.entity.*;
import io.tradeflow.ledger.exception.DuplicateIdempotencyKeyException;
import io.tradeflow.ledger.exception.InsufficientFundsException;
import io.tradeflow.ledger.exception.ReservationNotFoundException;
import io.tradeflow.ledger.model.AccountBalance;
import io.tradeflow.ledger.repository.AccountRepository;
import io.tradeflow.ledger.repository.IdempotencyKeyRepository;
import io.tradeflow.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LedgerEngine} verifying financial operations and idempotency contracts.
 *
 * <p>These are pure unit tests leveraging Mockito to isolate the engine from the database
 * and transaction managers. Entities are instantiated directly to avoid class-level mocking
 * issues on modern JDK versions (e.g. JDK 25 Byte Buddy limitations).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerEngine")
class LedgerEngineTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LedgerEngine ledgerEngine;

    @BeforeEach
    void setUp() {
        this.ledgerEngine = new LedgerEngine(
                accountRepository,
                ledgerEntryRepository,
                idempotencyKeyRepository,
                objectMapper
        );
    }

    private Account createAccount(Long id, String userId, String currencyCode) {
        Account account = new Account(userId, currencyCode);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    @Nested
    @DisplayName("getAccountBalance()")
    class GetAccountBalance {

        @Test
        @DisplayName("returns zero balance when user account does not exist")
        void returnsZeroBalanceWhenUserAccountDoesNotExist() {
            // Given
            String userId = "user-abc";
            String currencyCode = "USD";
            when(accountRepository.findByUserIdAndCurrencyCode(userId, currencyCode))
                    .thenReturn(Optional.empty());

            // When
            AccountBalance balance = ledgerEngine.getAccountBalance(userId, currencyCode);

            // Then
            assertAll(
                    () -> assertEquals(userId, balance.userId()),
                    () -> assertEquals(0L, balance.availableCents()),
                    () -> assertEquals(0L, balance.reservedCents()),
                    () -> assertEquals(currencyCode, balance.currencyCode())
            );
            verify(accountRepository).findByUserIdAndCurrencyCode(userId, currencyCode);
            verifyNoInteractions(ledgerEntryRepository);
        }

        @Test
        @DisplayName("returns zero reserved cents when system escrow account does not exist")
        void returnsZeroReservedCentsWhenSystemEscrowAccountDoesNotExist() {
            // Given
            String userId = "user-abc";
            String currencyCode = "USD";
            Account userAccount = createAccount(1L, userId, currencyCode);

            when(accountRepository.findByUserIdAndCurrencyCode(userId, currencyCode))
                    .thenReturn(Optional.of(userAccount));
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.CREDIT))
                    .thenReturn(5000L);
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.DEBIT))
                    .thenReturn(2000L);
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currencyCode))
                    .thenReturn(Optional.empty());

            // When
            AccountBalance balance = ledgerEngine.getAccountBalance(userId, currencyCode);

            // Then
            assertAll(
                    () -> assertEquals(userId, balance.userId()),
                    () -> assertEquals(3000L, balance.availableCents()),
                    () -> assertEquals(0L, balance.reservedCents()),
                    () -> assertEquals(currencyCode, balance.currencyCode())
            );
        }

        @Test
        @DisplayName("returns calculated available and reserved balance when account and entries exist")
        void returnsCalculatedAvailableAndReservedBalanceWhenAccountAndEntriesExist() {
            // Given
            String userId = "user-abc";
            String currencyCode = "USD";
            Account userAccount = createAccount(1L, userId, currencyCode);
            Account escrowAccount = createAccount(2L, SystemAccount.SYSTEM_ESCROW.getUserId(), currencyCode);

            when(accountRepository.findByUserIdAndCurrencyCode(userId, currencyCode))
                    .thenReturn(Optional.of(userAccount));
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.CREDIT))
                    .thenReturn(10000L);
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.DEBIT))
                    .thenReturn(3000L);
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currencyCode))
                    .thenReturn(Optional.of(escrowAccount));
            when(ledgerEntryRepository.sumReservedCentsByAccountIdAndEscrowAccountId(1L, 2L))
                    .thenReturn(1500L);

            // When
            AccountBalance balance = ledgerEngine.getAccountBalance(userId, currencyCode);

            // Then
            assertAll(
                    () -> assertEquals(userId, balance.userId()),
                    () -> assertEquals(7000L, balance.availableCents()),
                    () -> assertEquals(1500L, balance.reservedCents()),
                    () -> assertEquals(currencyCode, balance.currencyCode())
            );
        }
    }

    @Nested
    @DisplayName("creditWallet()")
    class CreditWallet {

        @Test
        @DisplayName("inserts credit entries and records idempotency key for a new request")
        void insertsCreditEntriesAndRecordsIdempotencyKeyForANewRequest() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-123";
            long amount = 2500L;
            String desc = "Direct Deposit";

            Account userAccount = createAccount(1L, userId, currency);
            Account systemFundingAccount = createAccount(2L, SystemAccount.SYSTEM_FUNDING.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_FUNDING.getUserId(), currency))
                    .thenReturn(Optional.of(systemFundingAccount));

            // When
            ledgerEngine.creditWallet(userId, currency, key, amount, desc);

            // Then
            verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository).save(argThat(idempotency -> 
                    key.equals(idempotency.getIdempotencyKey()) &&
                    idempotency.getOperation() == LedgerOperation.CREDIT_WALLET &&
                    "{\"success\": true}".equals(idempotency.getResponseBody())
            ));
        }

        @Test
        @DisplayName("returns early on duplicate request (success scenario)")
        void returnsEarlyOnDuplicateRequest() {
            // Given
            String key = "key-123";
            IdempotencyKey record = new IdempotencyKey(key, LedgerOperation.CREDIT_WALLET, "{\"success\": true}");
            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            // When
            ledgerEngine.creditWallet("user-abc", "USD", key, 100L, "re-credit");

            // Then
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws DuplicateIdempotencyKeyException when operation mismatch (key hijacked)")
        void throwsDuplicateIdempotencyKeyExceptionWhenOperationMismatch() {
            // Given
            String key = "key-123";
            IdempotencyKey record = new IdempotencyKey(key, LedgerOperation.RESERVE_FUNDS, "{}");
            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            // When / Then
            assertThrows(DuplicateIdempotencyKeyException.class, () ->
                    ledgerEngine.creditWallet("user-abc", "USD", key, 100L, "re-credit")
            );
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }
    }

    @Nested
    @DisplayName("account creation race recovery")
    class AccountRaceRecovery {

        @Test
        @DisplayName("getOrCreateAccount recovers when a concurrent transaction creates the " +
                "system account first")
        void getOrCreateAccountRecoversFromConcurrentCreation() {
            // Given: SYSTEM_FUNDING doesn't exist on our first read, but by the time our
            // insert fails on the unique constraint, another transaction has already created it.
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-123";

            Account userAccount = createAccount(1L, userId, currency);
            Account systemFundingAccount = createAccount(2L, SystemAccount.SYSTEM_FUNDING.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_FUNDING.getUserId(), currency))
                    .thenReturn(Optional.empty(), Optional.of(systemFundingAccount));
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_accounts_user_currency"));

            // When
            ledgerEngine.creditWallet(userId, currency, key, 500L, "deposit");

            // Then: re-fetched the row the other transaction created, and proceeded normally.
            verify(accountRepository, times(2))
                    .findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_FUNDING.getUserId(), currency);
            verify(ledgerEntryRepository).save(argThat(entry -> entry.getAccount() == systemFundingAccount));
        }

        @Test
        @DisplayName("getOrCreateAndLockAccount recovers when a concurrent transaction creates " +
                "the user account first")
        void getOrCreateAndLockAccountRecoversFromConcurrentCreation() {
            // Given: the user's account doesn't exist on our first locked read, but by the
            // time our insert fails on the unique constraint, another transaction created it.
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-123";

            Account userAccount = createAccount(1L, userId, currency);
            Account systemFundingAccount = createAccount(2L, SystemAccount.SYSTEM_FUNDING.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.empty(), Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_FUNDING.getUserId(), currency))
                    .thenReturn(Optional.of(systemFundingAccount));
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_accounts_user_currency"));

            // When
            ledgerEngine.creditWallet(userId, currency, key, 500L, "deposit");

            // Then: re-fetched (WITH the lock) the row the other transaction created.
            verify(accountRepository, times(2)).findByUserIdAndCurrencyCodeForUpdate(userId, currency);
            verify(ledgerEntryRepository).save(argThat(entry -> entry.getAccount() == userAccount));
        }
    }

    @Nested
    @DisplayName("reserveFunds()")
    class ReserveWalletFunds {

        @Test
        @DisplayName("throws DuplicateIdempotencyKeyException when operation mismatch (key hijacked)")
        void throwsDuplicateIdempotencyKeyExceptionWhenOperationMismatch() {
            // Given
            String key = "key-456";
            IdempotencyKey record = new IdempotencyKey(key, LedgerOperation.CREDIT_WALLET, "{}");
            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            // When / Then
            assertThrows(DuplicateIdempotencyKeyException.class, () ->
                    ledgerEngine.reserveFunds("user-abc", "USD", key, 500L, "order-999")
            );
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("returns cached reservationId on duplicate request matching RESERVE_FUNDS")
        void returnsCachedReservationIdOnDuplicateRequest() {
            // Given
            String key = "key-456";
            String responseBody = "{\"success\": true, \"reservationId\": \"key-456\"}";
            IdempotencyKey record = new IdempotencyKey(key, LedgerOperation.RESERVE_FUNDS, responseBody);
            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            // When
            String result = ledgerEngine.reserveFunds("user-abc", "USD", key, 500L, "order-999");

            // Then
            assertEquals(key, result);
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws InsufficientFundsException when available balance is below requested amount")
        void throwsInsufficientFundsExceptionWhenBalanceBelowRequested() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-456";
            long amount = 1000L;

            Account userAccount = createAccount(10L, userId, currency);
            Account escrowAccount = createAccount(20L, SystemAccount.SYSTEM_ESCROW.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currency))
                    .thenReturn(Optional.of(escrowAccount));

            // Mock credits and debits summing up to 500 available cents (1000 credits, 500 debits)
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(10L, EntryType.CREDIT))
                    .thenReturn(1000L);
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(10L, EntryType.DEBIT))
                    .thenReturn(500L);

            // When / Then
            assertThrows(InsufficientFundsException.class, () ->
                    ledgerEngine.reserveFunds(userId, currency, key, amount, "order-999")
            );
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository, never()).save(any(IdempotencyKey.class));
        }

        @Test
        @DisplayName("inserts debit/credit entries, records idempotency key, and returns reservationId when funds are sufficient")
        void insertsEntriesAndRecordsIdempotencyWhenFundsAreSufficient() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-456";
            long amount = 1000L;

            Account userAccount = createAccount(10L, userId, currency);
            Account escrowAccount = createAccount(20L, SystemAccount.SYSTEM_ESCROW.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currency))
                    .thenReturn(Optional.of(escrowAccount));

            // Mock credits and debits summing up to 1500 available cents (2000 credits, 500 debits)
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(10L, EntryType.CREDIT))
                    .thenReturn(2000L);
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(10L, EntryType.DEBIT))
                    .thenReturn(500L);

            // When
            String result = ledgerEngine.reserveFunds(userId, currency, key, amount, "order-999");

            // Then
            assertEquals(key, result);
            verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository).save(argThat(idempotency ->
                    key.equals(idempotency.getIdempotencyKey()) &&
                    idempotency.getOperation() == LedgerOperation.RESERVE_FUNDS &&
                    idempotency.getResponseBody().contains("reservationId")
            ));
        }
    }

    @Nested
    @DisplayName("debitWallet()")
    class DebitWallet {

        @Test
        @DisplayName("inserts debit/credit entries and records idempotency key when funds are sufficient")
        void insertsEntriesAndRecordsIdempotencyKeyWhenFundsAreSufficient() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-789";
            long amount = 1000L;
            String desc = "Withdrawal to bank";

            Account userAccount = createAccount(1L, userId, currency);
            Account systemFundingAccount = createAccount(2L, SystemAccount.SYSTEM_FUNDING.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_FUNDING.getUserId(), currency))
                    .thenReturn(Optional.of(systemFundingAccount));
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.CREDIT))
                    .thenReturn(2000L);
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.DEBIT))
                    .thenReturn(500L);

            // When
            ledgerEngine.debitWallet(userId, currency, key, amount, desc);

            // Then
            verify(ledgerEntryRepository, times(2)).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository).save(argThat(idempotency ->
                    key.equals(idempotency.getIdempotencyKey()) &&
                    idempotency.getOperation() == LedgerOperation.DEBIT_WALLET &&
                    "{\"success\": true}".equals(idempotency.getResponseBody())
            ));
        }

        @Test
        @DisplayName("returns early on duplicate request (success scenario)")
        void returnsEarlyOnDuplicateRequest() {
            // Given
            String key = "key-789";
            IdempotencyKey record = new IdempotencyKey(key, LedgerOperation.DEBIT_WALLET, "{\"success\": true}");
            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            // When
            ledgerEngine.debitWallet("user-abc", "USD", key, 100L, "withdrawal");

            // Then
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws DuplicateIdempotencyKeyException when operation mismatch (key hijacked)")
        void throwsDuplicateIdempotencyKeyExceptionWhenOperationMismatch() {
            // Given
            String key = "key-789";
            IdempotencyKey record = new IdempotencyKey(key, LedgerOperation.CREDIT_WALLET, "{}");
            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            // When / Then
            assertThrows(DuplicateIdempotencyKeyException.class, () ->
                    ledgerEngine.debitWallet("user-abc", "USD", key, 100L, "withdrawal")
            );
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws InsufficientFundsException when available balance is below requested amount")
        void throwsInsufficientFundsExceptionWhenBalanceBelowRequested() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String key = "key-789";
            long amount = 1000L;

            Account userAccount = createAccount(1L, userId, currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_FUNDING.getUserId(), currency))
                    .thenReturn(Optional.of(createAccount(2L, SystemAccount.SYSTEM_FUNDING.getUserId(), currency)));
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.CREDIT))
                    .thenReturn(500L);
            when(ledgerEntryRepository.sumAmountCentsByAccountIdAndEntryType(1L, EntryType.DEBIT))
                    .thenReturn(200L);

            // When / Then
            assertThrows(InsufficientFundsException.class, () ->
                    ledgerEngine.debitWallet(userId, currency, key, amount, "withdrawal")
            );
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository, never()).save(any(IdempotencyKey.class));
        }
    }

    @Nested
    @DisplayName("releaseFunds()")
    class ReleaseFunds {

        @Test
        @DisplayName("moves outstanding escrow funds back to the user and records idempotency key")
        void movesOutstandingEscrowFundsBackToUserAndRecordsIdempotencyKey() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String releaseKey = "release-key-1";
            String originalReservationId = "reserve-key-1";

            Account userAccount = createAccount(1L, userId, currency);
            Account escrowAccount = createAccount(2L, SystemAccount.SYSTEM_ESCROW.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(releaseKey)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currency))
                    .thenReturn(Optional.of(escrowAccount));
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.CREDIT)).thenReturn(1000L);
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.DEBIT)).thenReturn(0L);

            // When
            ledgerEngine.releaseFunds(userId, currency, releaseKey, originalReservationId, "order-999");

            // Then
            verify(ledgerEntryRepository).save(argThat(entry ->
                    entry.getAccount() == escrowAccount &&
                    entry.getEntryType() == EntryType.DEBIT &&
                    entry.getAmountCents() == 1000L &&
                    originalReservationId.equals(entry.getReferenceId())
            ));
            verify(ledgerEntryRepository).save(argThat(entry ->
                    entry.getAccount() == userAccount &&
                    entry.getEntryType() == EntryType.CREDIT &&
                    entry.getAmountCents() == 1000L &&
                    originalReservationId.equals(entry.getReferenceId())
            ));
            verify(idempotencyKeyRepository).save(argThat(idempotency ->
                    releaseKey.equals(idempotency.getIdempotencyKey()) &&
                    idempotency.getOperation() == LedgerOperation.RELEASE_FUNDS &&
                    "{\"success\": true}".equals(idempotency.getResponseBody())
            ));
        }

        @Test
        @DisplayName("returns early on duplicate request (success scenario)")
        void returnsEarlyOnDuplicateRequest() {
            // Given
            String releaseKey = "release-key-1";
            IdempotencyKey record = new IdempotencyKey(releaseKey, LedgerOperation.RELEASE_FUNDS, "{\"success\": true}");
            when(idempotencyKeyRepository.findByIdempotencyKey(releaseKey)).thenReturn(Optional.of(record));

            // When
            ledgerEngine.releaseFunds("user-abc", "USD", releaseKey, "reserve-key-1", "order-999");

            // Then
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws DuplicateIdempotencyKeyException when operation mismatch (key hijacked)")
        void throwsDuplicateIdempotencyKeyExceptionWhenOperationMismatch() {
            // Given
            String releaseKey = "release-key-1";
            IdempotencyKey record = new IdempotencyKey(releaseKey, LedgerOperation.CREDIT_WALLET, "{}");
            when(idempotencyKeyRepository.findByIdempotencyKey(releaseKey)).thenReturn(Optional.of(record));

            // When / Then
            assertThrows(DuplicateIdempotencyKeyException.class, () ->
                    ledgerEngine.releaseFunds("user-abc", "USD", releaseKey, "reserve-key-1", "order-999")
            );
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws ReservationNotFoundException when no outstanding hold exists for the reservation")
        void throwsReservationNotFoundExceptionWhenNoOutstandingHoldExists() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String releaseKey = "release-key-1";
            String originalReservationId = "reserve-key-1";

            Account userAccount = createAccount(1L, userId, currency);
            Account escrowAccount = createAccount(2L, SystemAccount.SYSTEM_ESCROW.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(releaseKey)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currency))
                    .thenReturn(Optional.of(escrowAccount));
            // Already fully released/captured: credited equals debited, nothing outstanding.
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.CREDIT)).thenReturn(1000L);
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.DEBIT)).thenReturn(1000L);

            // When / Then
            assertThrows(ReservationNotFoundException.class, () ->
                    ledgerEngine.releaseFunds(userId, currency, releaseKey, originalReservationId, "order-999")
            );
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository, never()).save(any(IdempotencyKey.class));
        }
    }

    @Nested
    @DisplayName("captureFunds()")
    class CaptureFunds {

        @Test
        @DisplayName("moves outstanding escrow funds to SYSTEM_REVENUE, locks the user account, and records idempotency key")
        void movesOutstandingEscrowFundsToRevenueLocksUserAndRecordsIdempotencyKey() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String captureKey = "capture-key-1";
            String originalReservationId = "reserve-key-1";

            Account userAccount = createAccount(1L, userId, currency);
            Account escrowAccount = createAccount(2L, SystemAccount.SYSTEM_ESCROW.getUserId(), currency);
            Account revenueAccount = createAccount(3L, SystemAccount.SYSTEM_REVENUE.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(captureKey)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currency))
                    .thenReturn(Optional.of(escrowAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_REVENUE.getUserId(), currency))
                    .thenReturn(Optional.of(revenueAccount));
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.CREDIT)).thenReturn(1000L);
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.DEBIT)).thenReturn(0L);

            // When
            ledgerEngine.captureFunds(userId, currency, captureKey, originalReservationId, "order-999");

            // Then
            // Critical: captureFunds never reads or writes the user's balance, but must still
            // acquire this lock — it's the same row releaseFunds locks, and is what prevents
            // a concurrent releaseFunds/captureFunds race on the same reservation from both
            // succeeding. See the TODO(task-1.9) on captureFunds for the real-locking IT this
            // mock can't cover.
            verify(accountRepository).findByUserIdAndCurrencyCodeForUpdate(userId, currency);

            verify(ledgerEntryRepository).save(argThat(entry ->
                    entry.getAccount() == escrowAccount &&
                    entry.getEntryType() == EntryType.DEBIT &&
                    entry.getAmountCents() == 1000L &&
                    originalReservationId.equals(entry.getReferenceId())
            ));
            verify(ledgerEntryRepository).save(argThat(entry ->
                    entry.getAccount() == revenueAccount &&
                    entry.getEntryType() == EntryType.CREDIT &&
                    entry.getAmountCents() == 1000L &&
                    originalReservationId.equals(entry.getReferenceId())
            ));
            verify(idempotencyKeyRepository).save(argThat(idempotency ->
                    captureKey.equals(idempotency.getIdempotencyKey()) &&
                    idempotency.getOperation() == LedgerOperation.CAPTURE_FUNDS &&
                    "{\"success\": true}".equals(idempotency.getResponseBody())
            ));
        }

        @Test
        @DisplayName("returns early on duplicate request (success scenario)")
        void returnsEarlyOnDuplicateRequest() {
            // Given
            String captureKey = "capture-key-1";
            IdempotencyKey record = new IdempotencyKey(captureKey, LedgerOperation.CAPTURE_FUNDS, "{\"success\": true}");
            when(idempotencyKeyRepository.findByIdempotencyKey(captureKey)).thenReturn(Optional.of(record));

            // When
            ledgerEngine.captureFunds("user-abc", "USD", captureKey, "reserve-key-1", "order-999");

            // Then
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws DuplicateIdempotencyKeyException when operation mismatch (key hijacked)")
        void throwsDuplicateIdempotencyKeyExceptionWhenOperationMismatch() {
            // Given
            String captureKey = "capture-key-1";
            IdempotencyKey record = new IdempotencyKey(captureKey, LedgerOperation.CREDIT_WALLET, "{}");
            when(idempotencyKeyRepository.findByIdempotencyKey(captureKey)).thenReturn(Optional.of(record));

            // When / Then
            assertThrows(DuplicateIdempotencyKeyException.class, () ->
                    ledgerEngine.captureFunds("user-abc", "USD", captureKey, "reserve-key-1", "order-999")
            );
            verifyNoInteractions(accountRepository, ledgerEntryRepository);
        }

        @Test
        @DisplayName("throws ReservationNotFoundException when no outstanding hold exists for the reservation")
        void throwsReservationNotFoundExceptionWhenNoOutstandingHoldExists() {
            // Given
            String userId = "user-abc";
            String currency = "USD";
            String captureKey = "capture-key-1";
            String originalReservationId = "reserve-key-1";

            Account userAccount = createAccount(1L, userId, currency);
            Account escrowAccount = createAccount(2L, SystemAccount.SYSTEM_ESCROW.getUserId(), currency);
            Account revenueAccount = createAccount(3L, SystemAccount.SYSTEM_REVENUE.getUserId(), currency);

            when(idempotencyKeyRepository.findByIdempotencyKey(captureKey)).thenReturn(Optional.empty());
            when(accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currency))
                    .thenReturn(Optional.of(userAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_ESCROW.getUserId(), currency))
                    .thenReturn(Optional.of(escrowAccount));
            when(accountRepository.findByUserIdAndCurrencyCode(SystemAccount.SYSTEM_REVENUE.getUserId(), currency))
                    .thenReturn(Optional.of(revenueAccount));
            // Already fully released/captured: credited equals debited, nothing outstanding.
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.CREDIT)).thenReturn(1000L);
            when(ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                    originalReservationId, 2L, EntryType.DEBIT)).thenReturn(1000L);

            // When / Then
            assertThrows(ReservationNotFoundException.class, () ->
                    ledgerEngine.captureFunds(userId, currency, captureKey, originalReservationId, "order-999")
            );
            verify(ledgerEntryRepository, never()).save(any(LedgerEntry.class));
            verify(idempotencyKeyRepository, never()).save(any(IdempotencyKey.class));
        }
    }
}
