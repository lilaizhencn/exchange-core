package exchange.core2.core.simulation;

/**
 * What kind of balance change a portfolio snapshot describes, which decides
 * whether it may be collapsed on its way out.
 *
 * <p>Both kinds carry the same thing - the client's whole balance at a command
 * boundary - so ordering between them still matters. They differ only in
 * whether an older one may be dropped when a newer one supersedes it.</p>
 */
public enum EmporiaPortfolioChange {

    /**
     * A completed change: a fill, or a funding/withdrawal adjustment. Every one
     * of these has to reach Emporia and be acknowledged individually, so they
     * are never collapsed - the audit record is one confirmed delivery per
     * completed change.
     */
    SETTLED,

    /**
     * A margin reservation moving: an order accepted or cancelled without
     * trading. It changes what the client can still spend, so a live view needs
     * it, but nothing is settled by it. Only the newest one per client carries
     * information, so an undelivered older one may be superseded.
     */
    RESERVED
}
