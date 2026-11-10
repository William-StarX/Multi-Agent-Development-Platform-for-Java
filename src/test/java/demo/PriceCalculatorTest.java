package demo;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceCalculatorTest {
    @Test void subtractsDiscount() { assertEquals(800, PriceCalculator.payable(1000, 200)); }
    @Test void floorsAtZero() { assertEquals(0, PriceCalculator.payable(100, 200)); }
    @Test void noDiscount() { assertEquals(1000, PriceCalculator.payable(1000, 0)); }
    @Test void equalDiscount() { assertEquals(0, PriceCalculator.payable(1000, 1000)); }
    @Test void emptyOrder() { assertEquals(0, PriceCalculator.payable(0, 0)); }
    @Test void supportsLongAmounts() {
        assertEquals(4_999_999_900L, PriceCalculator.payable(5_000_000_000L, 100));
    }
}
