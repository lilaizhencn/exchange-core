package exchange.core2.tests.unit;

import exchange.core2.core.simulation.SymbolPartitionDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SymbolPartitionDispatcherTest {

    @Test
    void shouldPreserveFifoPublicationWithinMatchingEnginePartitions() {
        final List<String> publicationOrder = new ArrayList<>();
        try (SymbolPartitionDispatcher dispatcher =
                     new SymbolPartitionDispatcher(4)) {
            final CompletableFuture<
                    SymbolPartitionDispatcher.PartitionResult<String>> first =
                    dispatcher.submit(0, () -> {
                        publicationOrder.add("first");
                        return CompletableFuture.completedFuture("a");
                    });
            final CompletableFuture<
                    SymbolPartitionDispatcher.PartitionResult<String>> second =
                    dispatcher.submit(4, () -> {
                        publicationOrder.add("second");
                        return CompletableFuture.completedFuture("b");
                    });
            final CompletableFuture<
                    SymbolPartitionDispatcher.PartitionResult<String>> other =
                    dispatcher.submit(
                            1,
                            () -> CompletableFuture.completedFuture("c"));

            assertEquals(0, first.join().partition());
            assertEquals(1, first.join().partitionSequence());
            assertEquals(0, second.join().partition());
            assertEquals(2, second.join().partitionSequence());
            assertEquals(1, other.join().partition());
            assertEquals(1, other.join().partitionSequence());
            assertEquals(List.of("first", "second"), publicationOrder);
        }
    }

    @Test
    void shouldRequirePowerOfTwoPartitionCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SymbolPartitionDispatcher(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SymbolPartitionDispatcher(3));
    }
}
