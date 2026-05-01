package com.example.stockportfolio.adapter.marketdata.kis;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트용 KisAccessTokenStore 인메모리 구현. 옵션으로 find/save 시 예외 throw 가능.
 */
final class InMemoryKisAccessTokenStore implements KisAccessTokenStore {

    private final AtomicReference<StoredToken> ref = new AtomicReference<>();
    boolean failOnFind;
    boolean failOnSave;
    int findCount;
    int saveCount;

    InMemoryKisAccessTokenStore() {}

    InMemoryKisAccessTokenStore(StoredToken seeded) {
        ref.set(seeded);
    }

    @Override
    public Optional<StoredToken> find() {
        findCount++;
        if (failOnFind) {
            throw new RuntimeException("simulated find failure");
        }
        return Optional.ofNullable(ref.get());
    }

    @Override
    public void save(StoredToken token) {
        saveCount++;
        if (failOnSave) {
            throw new RuntimeException("simulated save failure");
        }
        ref.set(token);
    }

    StoredToken peek() {
        return ref.get();
    }
}
