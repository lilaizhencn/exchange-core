/*
 * Copyright 2019 Maksim Zheravin
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
package exchange.core2.core.common.api;

import exchange.core2.core.common.OrderAction;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Atomically replaces the price and total quantity of a live order.
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiReplaceOrder extends ApiCommand {

    public final long orderId;
    public final long newPrice;
    public final long newQuantity;
    public final long newReservePrice;
    public final OrderAction side;
    public final long uid;
    public final int symbol;

    @Override
    public String toString() {
        return "[REPLACE " + orderId + " " + newPrice + "@" + newQuantity
                + " u" + uid + " s" + symbol + "]";
    }
}
