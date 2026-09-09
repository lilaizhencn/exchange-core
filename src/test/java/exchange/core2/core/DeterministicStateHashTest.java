package exchange.core2.core;

import exchange.core2.core.common.Order;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.orderbook.OrderBookDirectImpl.DirectOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Snapshot hashes must encode stable values, never JVM identity hashes of enums. */
class DeterministicStateHashTest {
    @Test
    void orderImplementationsHashStableWireValues() {
        for (OrderAction side : OrderAction.values()) {
            Order order = Order.builder().orderId(1).action(side).price(100).size(3)
                    .reserveBidPrice(100).filled(1).uid(7).build();
            DirectOrder direct = DirectOrder.builder().orderId(1).action(side).price(100).size(3)
                    .reserveBidPrice(100).filled(1).uid(7).build();
            int expected = Objects.hash(1L, side.getCode(), 100L, 3L, 100L, 1L, 7L);
            assertEquals(expected, order.stateHash());
            assertEquals(expected, direct.stateHash());
            assertEquals(order.hashCode(), direct.hashCode());
        }
    }

    @Test
    void separateJvmsWithDifferentIdentityAllocationProduceTheSameHash() throws Exception {
        assertEquals(child(0), child(10000));
    }

    private String child(int identities) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, ProcessMain.class.getName(), Integer.toString(identities))
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child JVM deadline");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output.lines().filter(line -> line.startsWith("hash=")).findFirst().orElseThrow();
        } finally { process.destroyForcibly(); }
    }

    public static final class ProcessMain {
        public static void main(String[] args) {
            for (int i = 0; i < Integer.parseInt(args[0]); i++) System.identityHashCode(new Object());
            Order order = Order.builder().orderId(1).action(OrderAction.ASK).price(100).size(3)
                    .reserveBidPrice(100).filled(1).uid(7).build();
            DirectOrder direct = DirectOrder.builder().orderId(1).action(OrderAction.ASK).price(100).size(3)
                    .reserveBidPrice(100).filled(1).uid(7).build();
            System.out.println("hash=" + order.stateHash() + ":" + direct.stateHash());
        }
    }
}
