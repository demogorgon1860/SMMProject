package com.smmpanel.controller;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public FAQ. Static content — there's no editorial workflow yet. Wired against the same shape
 * the frontend expects so that we can move to a CMS later without a frontend change.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/faq")
public class FaqController {

    private static final List<Map<String, String>> ENTRIES =
            List.of(
                    Map.of(
                            "id", "start-time",
                            "q", "How long until my order starts?",
                            "a",
                                    "Median start time is 47 seconds for Instagram services. The first"
                                        + " batch usually completes within 5 minutes; the rest dripfeeds"
                                        + " based on the service profile."),
                    Map.of(
                            "id", "payment",
                            "q", "What payment methods do you accept?",
                            "a",
                                    "Crypto only — USDT (TRC-20 + ERC-20), BTC, ETH, TON, LTC. No"
                                        + " cards, no PayPal, no bank transfer. USDT TRC-20 has the"
                                        + " lowest network fees."),
                    Map.of(
                            "id", "drops",
                            "q", "Why are my likes/followers dropping?",
                            "a",
                                    "Standard Instagram churn. We auto-detect and refill during the"
                                        + " 30-day refill window. No need to open a ticket — replacements"
                                        + " push automatically."),
                    Map.of(
                            "id", "cancel",
                            "q", "Can I cancel an order in progress?",
                            "a",
                                    "Pending orders cancel instantly with full refund. In-progress"
                                        + " orders cancel partial — delivered amount is charged, the"
                                        + " rest is refunded."),
                    Map.of(
                            "id", "rate-limit",
                            "q", "What's your API rate limit?",
                            "a",
                                    "60 requests/minute for read endpoints, 30 for writes. Higher"
                                        + " limits available — open a ticket from the API tab if you"
                                        + " need more."),
                    Map.of(
                            "id", "platforms",
                            "q", "Do you offer non-Instagram services?",
                            "a",
                                    "Not yet. TikTok and YouTube are scheduled for Q3 2026; X and"
                                        + " Telegram for Q4. Subscribe to the changelog to be notified."));

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> list() {
        return ResponseEntity.ok(ENTRIES);
    }
}
