/*
 * Copyright 2020 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.processors;

import exchange.core2.core.common.MatcherTradeEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public final class SharedPool {

    private final LinkedBlockingQueue<MatcherTradeEvent> eventChainsBuffer;
    private final MatcherTradeEvent[] singleThreadChains;
    private int singleThreadSize;

    @Getter
    private final int chainLength;

    public static SharedPool createTestSharedPool() {
        return new SharedPool(8, 4, 256);
    }

    /**
     * Create new shared pool
     *
     * @param poolMaxSize     - max size of pool. Will skip new chains if chains buffer is full.
     * @param poolInitialSize - initial number of pre-generated chains. Recommended to set higher than number of modules - (RE+ME)*2.
     * @param chainLength     - target chain length. Longer chain means rare requests for new chains. However longer chains can cause event placeholders starvation.
     */
    public SharedPool(final int poolMaxSize, final int poolInitialSize, final int chainLength) {

        this(poolMaxSize, poolInitialSize, chainLength, false);
    }

    /** Creates a bounded event-chain pool for a caller that already enforces single-thread ownership. */
    public static SharedPool createSingleThreadPool(
            final int poolMaxSize,
            final int poolInitialSize,
            final int chainLength) {
        return new SharedPool(poolMaxSize, poolInitialSize, chainLength, true);
    }

    private SharedPool(final int poolMaxSize, final int poolInitialSize,
                       final int chainLength, final boolean singleThreaded) {

        if (poolInitialSize > poolMaxSize) {
            throw new IllegalArgumentException("too big poolInitialSize");
        }

        this.eventChainsBuffer = singleThreaded ? null : new LinkedBlockingQueue<>(poolMaxSize);
        this.singleThreadChains = singleThreaded ? new MatcherTradeEvent[poolMaxSize] : null;
        this.chainLength = chainLength;

        for (int i = 0; i < poolInitialSize; i++) {
            final MatcherTradeEvent chain = MatcherTradeEvent.createEventChain(chainLength);
            if (singleThreaded) singleThreadChains[singleThreadSize++] = chain;
            else eventChainsBuffer.add(chain);
        }
    }

    /**
     * Request next chain from buffer
     * Threadsafe
     *
     * @return chain, otherwise null
     */
    public MatcherTradeEvent getChain() {
        MatcherTradeEvent poll;
        if (singleThreadChains != null) {
            poll = singleThreadSize == 0 ? null : singleThreadChains[--singleThreadSize];
            if (poll != null) singleThreadChains[singleThreadSize] = null;
        } else {
            poll = eventChainsBuffer.poll();
        }
//        log.debug("<<< POLL CHAIN HEAD  size={}", poll == null ? 0 : poll.getChainSize());
        if (poll == null) {
            poll = MatcherTradeEvent.createEventChain(chainLength);
        }

        return poll;
    }

    /**
     * Offers next chain.
     * Threadsafe (single producer safety is sufficient)
     *
     * @param head - pointer to the first element
     */
    public void putChain(MatcherTradeEvent head) {
        if (singleThreadChains != null) {
            if (singleThreadSize < singleThreadChains.length) singleThreadChains[singleThreadSize++] = head;
            return;
        }
        boolean offer = eventChainsBuffer.offer(head);
//        log.debug(">>> OFFER CHAIN HEAD  size={} orrder={}", head.getChainSize(), offer);
    }

}
