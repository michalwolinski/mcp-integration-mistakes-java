package com.wolinski.mcpmistakes.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * A deterministic, in-memory order book: the same size always yields the same orders,
 * so every byte count quoted in the article is reproducible.
 */
public final class OrderBook {

    private static final List<String> STATUSES = List.of("placed", "paid", "shipped", "delivered", "cancelled");

    private static final List<String> CUSTOMERS = List.of(
            "acme-logistics", "blue-harbor", "corvid-labs", "delta-foods", "evergreen-retail",
            "fjord-systems", "granite-works", "helios-energy");

    private final List<Order> orders;

    public OrderBook(int size) {
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(42L);
        List<Order> generated = new ArrayList<>(size);
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < size; i++) {
            int lineCount = 1 + random.nextInt(4);
            List<Order.Line> lines = new ArrayList<>(lineCount);
            BigDecimal total = BigDecimal.ZERO;
            for (int l = 0; l < lineCount; l++) {
                int quantity = 1 + random.nextInt(9);
                BigDecimal unitPrice = BigDecimal.valueOf(100 + random.nextInt(99_900), 2);
                lines.add(new Order.Line("SKU-%05d".formatted(random.nextInt(100_000)), quantity, unitPrice.toPlainString()));
                total = total.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            }
            generated.add(new Order(
                    "ORD-%06d".formatted(i + 1),
                    CUSTOMERS.get(random.nextInt(CUSTOMERS.size())),
                    STATUSES.get(random.nextInt(STATUSES.size())),
                    start.plusDays(random.nextInt(300)).toString(),
                    List.copyOf(lines),
                    total.toPlainString()));
        }
        this.orders = List.copyOf(generated);
    }

    public List<Order> all() {
        return orders;
    }

    public Optional<Order> find(String id) {
        return orders.stream().filter(order -> order.id().equals(id)).findFirst();
    }

    /** Orders with the given status, in id order, starting after {@code afterId} (exclusive). */
    public List<Order> page(String status, String afterId, int limit) {
        return orders.stream()
                .filter(order -> null == status || order.status().equals(status))
                .filter(order -> null == afterId || order.id().compareTo(afterId) > 0)
                .limit(limit)
                .toList();
    }
}
