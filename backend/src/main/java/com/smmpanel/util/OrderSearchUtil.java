package com.smmpanel.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Parsing helpers shared by the user- and admin-facing order search. */
public final class OrderSearchUtil {

    private OrderSearchUtil() {}

    /**
     * Hard cap on how many ids one search resolves to. Bounds the resulting {@code IN (:ids)} clause
     * so a deliberately huge paste-list can't turn the search into a slow/oversized query. Well
     * above any realistic manual search (the batch refill submit is separately capped at 100).
     */
    public static final int MAX_IDS = 200;

    /**
     * Parse a search box value into a list of order ids. Supports a single id ("29931") and a
     * comma/whitespace separated list ("29931, 29932 29933") so an operator can paste many order
     * numbers at once.
     *
     * <p>Returns the parsed ids (de-duplicated, input order preserved, capped at {@link #MAX_IDS})
     * ONLY when <em>every</em> token is a positive integer. Any non-numeric token (e.g. a link
     * fragment or username) yields an empty list, signalling the caller to fall back to its
     * link/username search. This keeps the single-id behaviour identical to the old {@code
     * Long.parseLong(term)} path while transparently extending it to many ids.
     */
    public static List<Long> parseOrderIds(String search) {
        if (search == null) return List.of();
        String trimmed = search.trim();
        if (trimmed.isEmpty()) return List.of();

        String[] tokens = trimmed.split("[,\\s]+");
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue; // leading/trailing separators
            long id;
            try {
                id = Long.parseLong(token);
            } catch (NumberFormatException e) {
                return List.of(); // mixed/non-numeric input → not an id search
            }
            if (id <= 0) return List.of(); // 0 / negatives aren't valid order ids
            ids.add(id);
            if (ids.size() >= MAX_IDS) break; // bound the IN clause; ignore the overflow
        }
        return ids.isEmpty() ? List.of() : new ArrayList<>(ids);
    }
}
