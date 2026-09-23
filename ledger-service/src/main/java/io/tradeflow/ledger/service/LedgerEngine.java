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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;

import java.util.Optional;

/**
 * Core engine for executing double-entry ledger transactions.
 *
 * <p>This service computes account balances dynamically via event sourcing (aggregating
 * immutable ledger entries) rather than relying on a mutable balance column. It enforces
 * strict idempotency to safely handle upstream RPC/network retries.
 *
 * <p><b>Concurrency & Thread Safety:</b>
 * This class is stateless and thread-safe. Methods mutating ledger state rely on
 * database-level pessimistic write locks ({@code SELECT ... FOR UPDATE}) and
 * {@code REPEATABLE_READ} isolation to prevent double-spend race conditions.
 */
@Service
public class LedgerEngine {

    private static final Logger log = LoggerFactory.getLogger(LedgerEngine.class);
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public LedgerEngine(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository,
                        IdempotencyKeyRepository idempotencyKeyRepository, ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Computes a user's available and reserved balance from the ledger.
     *
     * @param userId       the user to look up; must not be null
     * @param currencyCode ISO 4217 currency code; must not be null
     * @return available and reserved funds for that user and currency
     */
    @Transactional(readOnly = true)
    public AccountBalance getAccountBalance(String userId, String currencyCode) {
        //  No account yet means a zero balance, not an error.
        Optional<Account> fetchAccount = accountRepository.findByUserIdAndCurrencyCode(userId, currencyCode);
        if (fetchAccount.isEmpty()) {
            log.info("No active balance found for user {} in {}. Returning zero balance.", userId, currencyCode);
            return new AccountBalance(userId, 0L, 0L, currencyCode);
        }

        // TODO: this SUM() query will slow down as ledger_entries grows. Revisit with
        // periodic balance snapshots once volume justifies it.
        Account userAccount = getOrCreateAccount(userId, currencyCode);
        long userCredits = ledgerEntryRepository
                .sumAmountCentsByAccountIdAndEntryType(userAccount.getId(), EntryType.CREDIT);
        long userDebits = ledgerEntryRepository
                .sumAmountCentsByAccountIdAndEntryType(userAccount.getId(), EntryType.DEBIT);
        long availableCents = userCredits - userDebits;

        //  Reserved funds live in SYSTEM_ESCROW. No escrow account yet means nothing is reserved.
        Optional<Account> fetchEscrowAccount = accountRepository.findByUserIdAndCurrencyCode(
                SystemAccount.SYSTEM_ESCROW.getUserId(),
                currencyCode
        );

        long reservedCents = 0L;
        if (fetchEscrowAccount.isPresent()) {
            reservedCents = ledgerEntryRepository.sumReservedCentsByAccountIdAndEscrowAccountId(
                    userAccount.getId(),
                    fetchEscrowAccount.get().getId()
            );
        }

        return new AccountBalance(userId, availableCents, reservedCents, currencyCode);
    }

    /**
     * Credits a user's wallet from the central system funding account.
     *
     * @param userId         the user whose wallet is credited; must not be null
     * @param currencyCode   ISO 4217 currency code; must not be null
     * @param idempotencyKey client-provided UUID to ensure exactly-once execution; must not be null
     * @param amountCents    must be strictly positive
     * @param description    human-readable audit description for the ledger entries; must not be null
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void creditWallet(String userId, String currencyCode, String idempotencyKey, long amountCents,
                             String description) {

        //  1. Idempotency check.
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey record = existingKey.get();
            if (record.getOperation() != LedgerOperation.CREDIT_WALLET) {
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
            log.info("Duplicate creditWallet request detected for key {}. Returning cached success.", idempotencyKey);
            return;
        }

        //  2. Lock the user account. SYSTEM_FUNDING stays unlocked so concurrent credits
        //  for different users don't block each other.
        Account userAccount = getOrCreateAndLockAccount(userId, currencyCode);
        Account systemFundingAccount = getOrCreateAccount(SystemAccount.SYSTEM_FUNDING.getUserId(), currencyCode);

        //  3. Move the funds: SYSTEM_FUNDING down, user up.
        LedgerEntry ledgerEntryTypeDebit = new LedgerEntry(
                systemFundingAccount,
                EntryType.DEBIT,
                amountCents,
                idempotencyKey,
                "Funding source: " + description
        );

        LedgerEntry ledgerEntryTypeCredit = new LedgerEntry(
                userAccount,
                EntryType.CREDIT,
                amountCents,
                idempotencyKey,
                description
        );

        ledgerEntryRepository.save(ledgerEntryTypeDebit);
        ledgerEntryRepository.save(ledgerEntryTypeCredit);

        //  4. Save the idempotency record for this request.
        IdempotencyKey idempotencyEntry = new IdempotencyKey(
                idempotencyKey,
                LedgerOperation.CREDIT_WALLET,
                "{\"success\": true}"
        );

        idempotencyKeyRepository.save(idempotencyEntry);
    }

    /**
     * Holds funds in escrow against an open order.
     *
     * @param userId         the user whose funds are reserved; must not be null
     * @param currencyCode   ISO 4217 currency code; must not be null
     * @param idempotencyKey client-provided UUID; also returned as the reservation id
     *                       so a later {@link #releaseFunds} or {@link #captureFunds} call can reference it
     * @param amountCents    must be strictly positive
     * @param orderId        the order this reservation is held against; used for the
     *                       ledger entry descriptions, not for any business decision
     * @return the reservation id (same value as idempotencyKey)
     * @throws InsufficientFundsException if available balance < amountCents
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String reserveFunds(String userId, String currencyCode, String idempotencyKey, long amountCents,
                                     String orderId) {

        //  1. Idempotency check. Operation must match so a key can't be replayed
        //  against a different kind of request (e.g. CREDIT_WALLET key reused here).
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey record = existingKey.get();
            if (record.getOperation() != LedgerOperation.RESERVE_FUNDS) {
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
            try {
                JsonNode responseJson = objectMapper.readTree(record.getResponseBody());
                String cachedReservationId = responseJson.get("reservationId").asText();
                log.info("Duplicate reserveFunds request detected for key {}. Returning cached reservationId: {}.",
                        idempotencyKey, cachedReservationId
                );
                return cachedReservationId;
            } catch (Exception e) {
                log.error("Failed to parse cached response body for idempotency key: {}", idempotencyKey, e);
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
        }

        //  2. Lock the user account. SYSTEM_ESCROW stays unlocked so reservations for
        //  different users don't block each other.
        Account userAccount = getOrCreateAndLockAccount(userId, currencyCode);
        Account escrowAccount = getOrCreateAccount(SystemAccount.SYSTEM_ESCROW.getUserId(), currencyCode);

        //  3. Check the balance while the user account is locked, so no concurrent
        //  request can spend the same funds between this check and the save below.
        long userCredits = ledgerEntryRepository
                .sumAmountCentsByAccountIdAndEntryType(userAccount.getId(), EntryType.CREDIT);
        long userDebits = ledgerEntryRepository
                .sumAmountCentsByAccountIdAndEntryType(userAccount.getId(), EntryType.DEBIT);
        long availableFunds = userCredits - userDebits;

        if (availableFunds < amountCents) {
            throw new InsufficientFundsException(userId, amountCents, availableFunds);
        }

        //  4. Move the funds: user down, escrow up.
        LedgerEntry ledgerEntryTypeDebit = new LedgerEntry(
                userAccount,
                EntryType.DEBIT,
                amountCents,
                idempotencyKey,
                "Funds reserved for order: " + orderId
        );

        LedgerEntry ledgerEntryTypeCredit = new LedgerEntry(
                escrowAccount,
                EntryType.CREDIT,
                amountCents,
                idempotencyKey,
                "Escrow hold for order: " + orderId
        );

        ledgerEntryRepository.save(ledgerEntryTypeDebit);
        ledgerEntryRepository.save(ledgerEntryTypeCredit);

        //  5. Save the idempotency record, including the reservationId so a
        //  duplicate request can echo it back without redoing the reservation.
        IdempotencyKey idempotencyEntry = new IdempotencyKey(
                idempotencyKey,
                LedgerOperation.RESERVE_FUNDS,
                "{\"success\": true, \"reservationId\": \"" + idempotencyKey + "\"}"
        );
        idempotencyKeyRepository.save(idempotencyEntry);

        return idempotencyKey;
    }

    /**
     * Debits a user's wallet directly to the central system funding account
     * (e.g. withdrawal to bank). No reservation is involved — funds move once, immediately.
     *
     * @param userId            the user whose wallet is debited; must not be null
     * @param currencyCode      ISO 4217 currency code; must not be null
     * @param idempotencyKey    client-provided UUID to ensure exactly-once execution; must not be null
     * @param debitAmountCents  must be strictly positive
     * @param description       human-readable audit description for the ledger entries; must not be null
     * @throws InsufficientFundsException if available balance < debitAmountCents
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void debitWallet(String userId, String currencyCode, String idempotencyKey, long debitAmountCents,
                            String description) {

        //  1. Idempotency check.
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey record = existingKey.get();
            if (record.getOperation() != LedgerOperation.DEBIT_WALLET) {
                throw new DuplicateIdempotencyKeyException(idempotencyKey);
            }
            log.info("Duplicate debitWallet request detected for key {}. Returning cached success.", idempotencyKey);
            return;
        }

        //  2. Lock the user account. SYSTEM_FUNDING stays unlocked so concurrent debits
        //  for different users don't block each other.
        Account userAccount = getOrCreateAndLockAccount(userId, currencyCode);
        Account systemFundingAccount = getOrCreateAccount(SystemAccount.SYSTEM_FUNDING.getUserId(), currencyCode);

        //  3. Check the balance while the user account is locked.
        long userCredits = ledgerEntryRepository
                .sumAmountCentsByAccountIdAndEntryType(userAccount.getId(), EntryType.CREDIT);
        long userDebits = ledgerEntryRepository
                .sumAmountCentsByAccountIdAndEntryType(userAccount.getId(), EntryType.DEBIT);
        long availableCents = userCredits - userDebits;

        if (availableCents < debitAmountCents) {
            throw new InsufficientFundsException(userId, debitAmountCents, availableCents);
        }

        //  4. Move the funds: user down, SYSTEM_FUNDING up.
        LedgerEntry ledgerEntryTypeDebit = new LedgerEntry(
                userAccount,
                EntryType.DEBIT,
                debitAmountCents,
                idempotencyKey,
                description
        );

        LedgerEntry ledgerEntryTypeCredit = new LedgerEntry(
                systemFundingAccount,
                EntryType.CREDIT,
                debitAmountCents,
                idempotencyKey,
                description
        );

        ledgerEntryRepository.save(ledgerEntryTypeDebit);
        ledgerEntryRepository.save(ledgerEntryTypeCredit);

        //  5. Save the idempotency record for this request.
        IdempotencyKey idempotencyEntry = new IdempotencyKey(
                idempotencyKey,
                LedgerOperation.DEBIT_WALLET,
                "{\"success\": true}"
        );

        idempotencyKeyRepository.save(idempotencyEntry);
    }

    /**
     * Cancels a reservation and returns the held funds from escrow to the user.
     *
     * <p>The amount is read from the ledger, not passed in by the caller, so a bad
     * request can't release the wrong sum.
     *
     * @param userId                 the user the funds are returned to; must not be null.
     *                               Must be the same user the original reservation was made for.
     * @param currencyCode           ISO 4217 currency code; must not be null
     * @param releaseIdempotencyKey  unique id for this release request; must not be null
     * @param originalReservationId  id returned by {@link #reserveFunds}, identifying
     *                               which reservation to cancel; must not be null
     * @param orderId                the order this release is for; used for the ledger
     *                               entry descriptions, not for any business decision
     * @throws ReservationNotFoundException if no outstanding hold exists for originalReservationId
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void releaseFunds(String userId, String currencyCode, String releaseIdempotencyKey,
                             String originalReservationId, String orderId) {

        //  1. Idempotency check for this release request.
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(releaseIdempotencyKey);

        if (existingKey.isPresent()) {
            IdempotencyKey record = existingKey.get();

            if (record.getOperation() != LedgerOperation.RELEASE_FUNDS) {
                throw new DuplicateIdempotencyKeyException(releaseIdempotencyKey);
            }
            log.info("Duplicate releaseFunds request detected for key {}. Returning cached success.",
                    releaseIdempotencyKey);
            return;
        }

        //  2. Lock the user account. SYSTEM_ESCROW stays unlocked so releases for
        //  different users/reservations don't block each other.
        Account userAccount = getOrCreateAndLockAccount(userId, currencyCode);
        Account escrowAccount = getOrCreateAccount(SystemAccount.SYSTEM_ESCROW.getUserId(), currencyCode);

        //  3. Calculate how much is still held for this reservation.
        long escrowCredited = ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                originalReservationId, escrowAccount.getId(), EntryType.CREDIT);
        long escrowDebited = ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                originalReservationId, escrowAccount.getId(), EntryType.DEBIT);
        long outstandingCents = escrowCredited - escrowDebited;

        //  Nothing held means the reservation doesn't exist or was already released/captured.
        if (outstandingCents <= 0) {
            throw new ReservationNotFoundException(originalReservationId);
        }

        //  4. Move the funds: escrow down, user up. Both entries use originalReservationId
        //  so they stay linked to the original reservation, not to this release request.
        LedgerEntry ledgerEntryTypeDebit = new LedgerEntry(
                escrowAccount,
                EntryType.DEBIT,
                outstandingCents,
                originalReservationId,
                "Released escrow hold for order: " + orderId
        );

        LedgerEntry ledgerEntryTypeCredit = new LedgerEntry(
                userAccount,
                EntryType.CREDIT,
                outstandingCents,
                originalReservationId,
                "Reservation released for order: " + orderId
        );

        ledgerEntryRepository.save(ledgerEntryTypeDebit);
        ledgerEntryRepository.save(ledgerEntryTypeCredit);

        //  5. Save the idempotency record for this release request.
        IdempotencyKey idempotencyEntry = new IdempotencyKey(
                releaseIdempotencyKey,
                LedgerOperation.RELEASE_FUNDS,
                "{\"success\": true}"
        );

        idempotencyKeyRepository.save(idempotencyEntry);
    }

    /**
     * Finalizes a reservation, moving held funds from escrow to SYSTEM_REVENUE
     * (e.g. order fulfilled — payment is now final, not just held).
     *
     * <p>The amount is read from the ledger, same as {@link #releaseFunds} — not
     * passed in by the caller.
     *
     * <p>TODO(task-1.9): the claim that the {@code userId} lock below actually prevents
     * a concurrent {@link #releaseFunds}/{@code captureFunds} race on the same reservation
     * is reasoned, not verified — {@code LedgerEngineTest} mocks the repositories, so it
     * never exercises real {@code SELECT ... FOR UPDATE} row locking. Needs a Testcontainers
     * IT that fires both methods concurrently against a real MySQL instance for the same
     * reservationId and asserts exactly one succeeds.
     *
     * @param userId                 the user the original reservation was made for; must not be null.
     *                               Not credited or debited here — only used to acquire the same
     *                               per-user lock {@link #releaseFunds} uses, so a concurrent release
     *                               and capture of the same reservation can't both succeed.
     * @param currencyCode           ISO 4217 currency code; must not be null
     * @param captureIdempotencyKey  unique id for this capture request; must not be null
     * @param originalReservationId  id returned by {@link #reserveFunds}, identifying
     *                               which reservation to finalize; must not be null
     * @param orderId                the order this capture is for; used for the ledger
     *                               entry descriptions, not for any business decision
     * @throws ReservationNotFoundException if no outstanding hold exists for originalReservationId
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void captureFunds(String userId, String currencyCode, String captureIdempotencyKey,
                             String originalReservationId, String orderId) {

        //  1. Idempotency check for this capture request.
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByIdempotencyKey(captureIdempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey record = existingKey.get();
            if (record.getOperation() != LedgerOperation.CAPTURE_FUNDS) {
                throw new DuplicateIdempotencyKeyException(captureIdempotencyKey);
            }
            log.info("Duplicate captureFunds request detected for key {}. Returning cached success.",
                    captureIdempotencyKey);
            return;
        }

        //  2. Lock the user account. captureFunds never reads or writes the user's own
        //  balance, but this lock is still required: it's the same row releaseFunds locks,
        //  so it serializes a concurrent releaseFunds/captureFunds race on the same
        //  reservation. Without it, both could read the same outstanding balance before
        //  either writes, and both would proceed — double-processing one reservation.
        getOrCreateAndLockAccount(userId, currencyCode);
        Account escrowAccount = getOrCreateAccount(SystemAccount.SYSTEM_ESCROW.getUserId(), currencyCode);
        Account revenueAccount = getOrCreateAccount(SystemAccount.SYSTEM_REVENUE.getUserId(), currencyCode);

        //  3. Calculate how much is still held for this reservation.
        long escrowCredited = ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                originalReservationId, escrowAccount.getId(), EntryType.CREDIT);
        long escrowDebited = ledgerEntryRepository.sumAmountCentsByReferenceIdAndAccountIdAndEntryType(
                originalReservationId, escrowAccount.getId(), EntryType.DEBIT);
        long outstandingCents = escrowCredited - escrowDebited;

        //  Nothing held means the reservation doesn't exist or was already released/captured.
        if (outstandingCents <= 0) {
            throw new ReservationNotFoundException(originalReservationId);
        }

        //  4. Move the funds: escrow down, SYSTEM_REVENUE up. Both entries use
        //  originalReservationId so they stay linked to the original reservation.
        LedgerEntry ledgerEntryTypeDebit = new LedgerEntry(
                escrowAccount,
                EntryType.DEBIT,
                outstandingCents,
                originalReservationId,
                "Captured escrow hold for order: " + orderId
        );

        LedgerEntry ledgerEntryTypeCredit = new LedgerEntry(
                revenueAccount,
                EntryType.CREDIT,
                outstandingCents,
                originalReservationId,
                "Captured funds for order: " + orderId
        );

        ledgerEntryRepository.save(ledgerEntryTypeDebit);
        ledgerEntryRepository.save(ledgerEntryTypeCredit);

        //  5. Save the idempotency record for this capture request.
        IdempotencyKey idempotencyEntry = new IdempotencyKey(
                captureIdempotencyKey,
                LedgerOperation.CAPTURE_FUNDS,
                "{\"success\": true}"
        );

        idempotencyKeyRepository.save(idempotencyEntry);
    }

    /**
     * Finds an account, creating it if missing. Does not lock the row — use for
     * read paths and system accounts, not for balance checks before a write.
     */
    private Account getOrCreateAccount(String userId, String currencyCode) {
        Optional<Account> fetchedAccount = accountRepository.findByUserIdAndCurrencyCode(userId, currencyCode);
        if (fetchedAccount.isPresent()) {
            return fetchedAccount.get();
        }

        try {
            Account newAccount = new Account(userId, currencyCode);
            accountRepository.save(newAccount);
            return newAccount;
        } catch (DataIntegrityViolationException e) {
            //  Another transaction created this account between our SELECT and INSERT.
            //  uq_accounts_user_currency rejected our insert — re-fetch the row it created.
            return accountRepository.findByUserIdAndCurrencyCode(userId, currencyCode)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Finds an account and locks the row ({@code SELECT ... FOR UPDATE}), creating
     * it if missing. Required before any balance check that precedes a write.
     */
    private Account getOrCreateAndLockAccount(String userId, String currencyCode) {
        Optional<Account> fetchedAccount = accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currencyCode);
        if (fetchedAccount.isPresent()) {
            return fetchedAccount.get();
        }

        try {
            Account account = new Account(userId, currencyCode);
            accountRepository.save(account);
            return account;
        } catch (DataIntegrityViolationException e) {
            //  Another transaction created this account between our SELECT and INSERT.
            //  Re-fetch WITH the lock this time — the row exists now, so FOR UPDATE can hold it.
            return accountRepository.findByUserIdAndCurrencyCodeForUpdate(userId, currencyCode)
                    .orElseThrow(() -> e);
        }
    }
}
