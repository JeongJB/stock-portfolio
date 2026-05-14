package com.example.stockportfolio.adapter.marketdata.kis;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트용 FxRateStore 인메모리 구현. 옵션으로 find/save 시 예외 throw 가능.
 */
final class InMemoryFxRateStore implements FxRateStore {

    private final AtomicReference<StoredRate> ref = new AtomicReference<>();
    boolean failOnFind;
    boolean failOnSave;
    int findCount;
    int saveCount;

    InMemoryFxRateStore() {}

    InMemoryFxRateStore(StoredRate seeded) {
        ref.set(seeded);
    }

    @Override
    public Optional<StoredRate> find() {
        findCount++;
        if (failOnFind) {
            throw new RuntimeException("simulated find failure");
        }
        return Optional.ofNullable(ref.get());
    }

    @Override
    public void save(StoredRate rate) {
        saveCount++;
        if (failOnSave) {
            throw new RuntimeException("simulated save failure");
        }
        ref.set(rate);
    }

    StoredRate peek() {
        return ref.get();
    }
}
