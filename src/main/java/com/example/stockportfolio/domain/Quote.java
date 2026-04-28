package com.example.stockportfolio.domain;

import java.time.Instant;

public record Quote(String ticker, Exchange exchange, Money price, Instant asOf) {
}
