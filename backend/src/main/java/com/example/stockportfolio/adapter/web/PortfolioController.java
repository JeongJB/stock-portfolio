package com.example.stockportfolio.adapter.web;

import com.example.stockportfolio.adapter.web.dto.PortfolioView;
import com.example.stockportfolio.adapter.web.dto.RecordTradeRequest;
import com.example.stockportfolio.adapter.web.dto.RecordTradeResponse;
import com.example.stockportfolio.adapter.web.dto.SnapshotListResponse;
import com.example.stockportfolio.adapter.web.dto.SnapshotView;
import com.example.stockportfolio.adapter.web.dto.TradeView;
import com.example.stockportfolio.application.PortfolioApplicationService;
import com.example.stockportfolio.domain.Trade;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PortfolioController {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int MIN_LIMIT = 1;

    private final PortfolioApplicationService service;

    public PortfolioController(PortfolioApplicationService service) {
        this.service = service;
    }

    @PostMapping("/trades")
    public ResponseEntity<RecordTradeResponse> recordTrade(@Valid @RequestBody RecordTradeRequest request) {
        Trade trade = service.recordTrade(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecordTradeResponse.from(trade));
    }

    @GetMapping("/trades")
    public List<TradeView> recentTrades(
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit) {
        // 1~200 범위 강제 — 그 외는 400 (handler가 IllegalArgumentException을 매핑)
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit 은 " + MIN_LIMIT + "~" + MAX_LIMIT + " 범위여야 한다 (입력: " + limit + ")");
        }
        return service.recentTrades(limit);
    }

    @GetMapping("/portfolio")
    public PortfolioView portfolio() {
        return service.view();
    }

    @DeleteMapping("/trades/{id}")
    public PortfolioView deleteTrade(@PathVariable("id") String id) {
        // 1) replay 검증 + 단일 트랜잭션 삭제 (실패 시 NoSuchElementException → 404,
        //    TradeReplayValidationException → 422; ApiExceptionHandler 가 매핑).
        service.deleteTrade(id);
        // 2) 삭제 후 갱신된 포트폴리오 뷰를 본문으로 돌려줘 프론트가 별도 GET 없이 즉시 반영 가능.
        return service.view();
    }

    @PostMapping("/snapshots")
    public SnapshotView takeSnapshot() {
        return service.takeSnapshot();
    }

    @GetMapping("/snapshots")
    public SnapshotListResponse listSnapshots(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new SnapshotListResponse(service.listSnapshots(from, to));
    }
}
