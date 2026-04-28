package com.smmpanel.controller;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public FAQ. Static content — there's no editorial workflow yet. Wired against the same shape the
 * frontend expects so that we can move to a CMS later without a frontend change.
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
                                    "Median start time is 47 seconds for Instagram services. The"
                                            + " first batch usually completes within 5 minutes; the"
                                            + " rest dripfeeds based on the service profile."),
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
                                    "Standard Instagram churn — the platform itself filters or"
                                            + " unfollows over time. If you see a drop within 30"
                                            + " days of the order completing, request a free"
                                            + " refill from the order detail page. An operator"
                                            + " reviews the request and re-runs the order at the"
                                            + " dropped quantity. We do not monitor every order"
                                            + " automatically; the refill window starts when the"
                                            + " order is marked completed."),
                    Map.of(
                            "id", "cancel",
                            "q", "Can I cancel an order in progress?",
                            "a",
                                    "Pending orders cancel instantly with full refund. In-progress"
                                        + " orders cancel partial — delivered amount is charged,"
                                        + " the rest is refunded."),
                    Map.of(
                            "id", "rate-limit",
                            "q", "What's your API rate limit?",
                            "a",
                                    "Read endpoints: 60 requests per minute. Order creation: 10 per"
                                        + " minute. Auth flows: 5 per minute. Need higher limits"
                                        + " for a production integration? Open a ticket and explain"
                                        + " your traffic profile."),
                    Map.of(
                            "id", "platforms",
                            "q", "Do you offer non-Instagram services?",
                            "a",
                                    "Not yet. TikTok, X, Telegram and Facebook are on the"
                                            + " roadmap and will come online as we build the bot"
                                            + " infrastructure for each. No firm dates — subscribe"
                                            + " to the changelog to be notified."));

    @GetMapping
    public ResponseEntity<List<Map<String, String>>> list() {
        return ResponseEntity.ok(ENTRIES);
    }
}
