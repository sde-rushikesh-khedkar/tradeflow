package io.tradeflow.ledger.repository;

import io.tradeflow.ledger.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Account} entities.
 *
 * <p>Provides CRUD operations via {@link JpaRepository} and a single derived
 * query for looking up an account by its natural business key
 * (user ID + currency code).
 *
 * <p><b>Why no @Transactional here:</b> transaction boundaries belong on the
 * service layer ({@code LedgerEngine}), not the repository. Spring Data's
 * default repository methods are individually transactional, but multi-step
 * operations — read balance, check funds, write entries — must share a single
 * transaction managed by the service.
 *
 * <p><b>Thread safety:</b> thread-safe — Spring Data generates a singleton proxy.
 *
 * <p><b>Spring context:</b> singleton.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Finds an account by its natural business key.
     *
     * <p>Used by {@code LedgerEngine} to resolve an account before posting
     * ledger entries. Returns {@link Optional#empty()} if no account exists
     * for the given user and currency — the caller is responsible for throwing
     * {@code AccountNotFoundException}.
     *
     * @param userId        external user identifier; must not be null
     * @param currencyCode  ISO 4217 currency code; must not be null
     * @return              the account, or empty if not found
     */
    Optional<Account> findByUserIdAndCurrencyCode(String userId, String currencyCode);

    /**
      * Finds and pessimistically locks an account row for writing (SELECT ... FOR UPDATE).
      *
      * <p>This blocks concurrent threads from reading or modifying the account balance
      * until the holding transaction commits or rolls back, preventing double-spends.
      */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.userId = :userId AND a.currencyCode = :currencyCode")
    Optional<Account> findByUserIdAndCurrencyCodeForUpdate(@Param("userId") String userId,
                                                           @Param("currencyCode") String currencyCode
    );
}
