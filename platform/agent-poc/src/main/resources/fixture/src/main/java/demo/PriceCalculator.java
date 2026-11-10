package demo;

public final class PriceCalculator {
    public static long payable(long subtotalCents, long discountCents) {
        return subtotalCents + discountCents;
    }
}
