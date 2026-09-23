package io.tradeflow.ledger.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IdempotencyKey} entity construction and field contract.
 *
 * <p>No Spring context, no database — pure Java construction tests.
 * Duplicate key enforcement (UNIQUE constraint) is verified in task-1.9 IT tests
 * against a real MySQL instance via Testcontainers.
 */
@DisplayName("IdempotencyKey")
class IdempotencyKeyTest {

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("sets all fields correctly when constructed with valid arguments")
        void setsAllFieldsCorrectlyWhenConstructedWithValidArguments() {
            IdempotencyKey record = new IdempotencyKey(
                "uuid-abc-123", LedgerOperation.RESERVE_FUNDS, "{\"status\":\"success\"}");

            assertAll(
                () -> assertEquals("uuid-abc-123",          record.getIdempotencyKey()),
                () -> assertEquals(LedgerOperation.RESERVE_FUNDS, record.getOperation()),
                () -> assertEquals("{\"status\":\"success\"}", record.getResponseBody())
            );
        }

        @Test
        @DisplayName("returns null id before persistence")
        void returnsNullIdBeforePersistence() {
            IdempotencyKey record = new IdempotencyKey("uuid-abc-123", LedgerOperation.RESERVE_FUNDS, "{}");

            assertNull(record.getId());
        }

        @Test
        @DisplayName("returns null createdAt before persistence")
        void returnsNullCreatedAtBeforePersistence() {
            IdempotencyKey record = new IdempotencyKey("uuid-abc-123", LedgerOperation.RESERVE_FUNDS, "{}");

            assertNull(record.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability {

        @Test
        @DisplayName("exposes no setter methods")
        void exposesNoSetterMethods() {
            long setterCount = java.util.Arrays.stream(IdempotencyKey.class.getMethods())
                .filter(m -> m.getName().startsWith("set"))
                .count();

            assertEquals(0, setterCount, "IdempotencyKey must expose no setters — records are immutable after insert");
        }
    }
}
