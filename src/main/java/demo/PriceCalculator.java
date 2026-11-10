package demo;

public final class PriceCalculator {
    /** Applies a nonnegative discount and floors the payable amount at zero. */
    public static long payable(long subtotalCents, long discountCents) {
        return Math.max(0, subtotalCents - discountCents);
    }
}
