package io.tradeflow.ledger.repository;

import io.tradeflow.ledger.entity.EntryType;
import io.tradeflow.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link LedgerEntry} entities.
 *
 * <p>The most critical method here is {@link #sumAmountCentsByAccountIdAndEntryType},
 * which is the foundation of balance calculation in the double-entry model.
 * Balance is never stored — it is always derived by calling this method twice
 * (once for CREDIT, once for DEBIT) and computing the difference.
 *
 * <p><b>Why a native query for balance:</b> the balance calculation is a single
 * aggregation ({@code SUM}) over a filtered set of rows. A native SQL query
 * maps directly to the schema, is readable, and avoids the verbosity of a JPQL
 * CASE expression with fully-qualified enum references. The query is simple
 * enough that the loss of database portability is not a concern — MySQL 8 is
 * the declared and documented database for this service.
 *
 * <p><b>Why no @Transactional here:</b> transaction boundaries belong on
 * {@code LedgerEngine}. The balance query and the subsequent entry inserts
 * must execute inside the same {@code REPEATABLE_READ} transaction to prevent
 * concurrent overdrafts. Placing {@code @Transactional} here would open a
 * separate transaction for the balance query alone, breaking the isolation guarantee.
 *
 * <p><b>Thread safety:</b> thread-safe — Spring Data generates a singleton proxy.
 *
 * <p><b>Spring context:</b> singleton.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * Sums all ledger entry amounts for a given account and entry direction.
     *
     * <p>Called twice by {@code LedgerEngine.calculateBalance()}:
     * once with {@link EntryType#CREDIT} and once with {@link EntryType#DEBIT}.
     * Balance = credits - debits.
     *
     * <p>{@code COALESCE(..., 0)} ensures a zero is returned when the account
     * has no entries of the given type, rather than {@code null} — which would
     * cause a {@code NullPointerException} when unboxed to {@code long}.
     *
     * @param accountId  the primary key of the account; must not be null
     * @param entryType  DEBIT or CREDIT; must not be null
     * @return           sum of {@code amount_cents} for the given account and direction,
     *                   or 0 if no matching entries exist
     */
    @Query(value = """
            SELECT COALESCE(SUM(amount_cents), 0)
            FROM ledger_entries
            WHERE account_id = :accountId
                AND entry_type = :#{#entryType.name()}
            """, nativeQuery = true)
    long sumAmountCentsByAccountIdAndEntryType(@Param("accountId") Long accountId,
                                               @Param("entryType") EntryType entryType);

     /**
      * Sums all reserved cents for a user's account that are currently sitting in escrow.
      *
      * <p>An active reservation is defined as a DEBIT on the user's account whose
      * referenceId (idempotency key) is currently credited in SYSTEM_ESCROW but has
      * NOT yet been debited (captured or released) from SYSTEM_ESCROW.
      */
    @Query(value = """ 
            SELECT COALESCE(SUM(le.amount_cents), 0)
            FROM ledger_entries le
            WHERE le.account_id = :userAccountId
                --  1. Must be a debit on the user's account (indicating money was moved to escrow)
                AND le.entry_type = 'DEBIT'
                --  2. There must be a matching credit in escrow
                AND EXISTS(
                    SELECT 1 FROM ledger_entries esc_in
                    WHERE esc_in.reference_id = le.reference_id
                    AND esc_in.account_id = :escrowAccountId
                    AND esc_in.entry_type = 'CREDIT'
                )
                --  3. There must NOT be a matching debit in escrow (meaning it hasn't been captured/released yet)
                AND NOT EXISTS(
                    SELECT 1 FROM ledger_entries esc_out
                    WHERE esc_out.reference_id = le.reference_id
                    AND esc_out.account_id = :escrowAccountId
                    AND esc_out.entry_type = 'DEBIT'
                );
            """, nativeQuery = true)
    long sumReservedCentsByAccountIdAndEscrowAccountId(@Param("userAccountId") Long userAccountId,
                                                       @Param("escrowAccountId") Long escrowAccountId);

    /**
     * Sums entry amounts for one specific reservation event, scoped to a single
     * account and entry direction.
     *
     * <p>Used by release/capture flows to determine the outstanding escrow amount
     * for a given {@code reference_id} — {@code SUM(CREDIT) - SUM(DEBIT)} on the
     * escrow account for that reference_id. A result of zero means the reservation
     * was never created for that id, or has already been fully released/captured.
     *
     * <p>Unlike {@link #sumAmountCentsByAccountIdAndEntryType}, which aggregates
     * across ALL reservations for an account, this narrows to one reservation
     * event so a release call cannot accidentally act on unrelated escrow entries.
     *
     * @param referenceId  the idempotency key of the original reserving operation; must not be null
     * @param accountId    the primary key of the account (typically SYSTEM_ESCROW); must not be null
     * @param entryType    DEBIT or CREDIT; must not be null
     * @return             sum of {@code amount_cents} for the given reference_id, account, and
     *                     direction, or 0 if no matching entries exist
     */
    @Query(value = """
            SELECT COALESCE(SUM(amount_cents), 0)
            FROM ledger_entries
            WHERE reference_id = :referenceId
                AND account_id = :accountId
                AND entry_type = :#{#entryType.name()}
            """, nativeQuery = true)
    long sumAmountCentsByReferenceIdAndAccountIdAndEntryType(@Param("referenceId") String referenceId,
                                                             @Param("accountId") Long accountId,
                                                             @Param("entryType") EntryType entryType);
}
