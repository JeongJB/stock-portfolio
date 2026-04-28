package com.example.stockportfolio.domain;

import java.math.BigDecimal;

public interface MarketDataPort {

    Quote getQuote(String ticker, Exchange exchange);

    BigDecimal getUsdKrwRate();
}
