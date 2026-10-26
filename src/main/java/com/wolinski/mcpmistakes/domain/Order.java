package com.wolinski.mcpmistakes.domain;

import java.util.List;

public record Order(String id, String customer, String status, String placedAt, List<Line> lines, String total) {

    public record Line(String sku, int quantity, String unitPrice) {
    }
}
