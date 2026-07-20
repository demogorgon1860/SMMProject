package com.smmpanel.dto.instagram;

/** Supported Instagram order types for the bot. */
public enum InstagramOrderType {
    LIKE("like"),
    COMMENT("comment"),
    FOLLOW("follow"),
    LIKE_FOLLOW("like_follow"),
    LIKE_COMMENT("like_comment"),
    LIKE_COMMENT_FOLLOW("like_comment_follow");

    private final String value;

    InstagramOrderType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    // ---- Affected-metric bitmask (metric-aware per-URL order serialization) ----
    // Two same-link orders conflict — and must dispatch one-at-a-time — only when their masks
    // overlap (bitwise AND != 0). Independent metrics (e.g. likes vs comments) run in parallel.

    /** Order affects the like count. */
    public static final int METRIC_LIKE = 1;

    /** Order affects the comment count. */
    public static final int METRIC_COMMENT = 2;

    /** Order affects the follower count. */
    public static final int METRIC_FOLLOW = 4;

    /**
     * All metrics — the conservative mask for an unknown/unset order (serializes with everything).
     */
    public static final int METRIC_ALL = METRIC_LIKE | METRIC_COMMENT | METRIC_FOLLOW;

    /**
     * The set of counts this order type mutates, as a {@link #METRIC_LIKE}/COMMENT/FOLLOW bitmask.
     */
    public int metricMask() {
        return switch (this) {
            case LIKE -> METRIC_LIKE;
            case COMMENT -> METRIC_COMMENT;
            case FOLLOW -> METRIC_FOLLOW;
            case LIKE_FOLLOW -> METRIC_LIKE | METRIC_FOLLOW;
            case LIKE_COMMENT -> METRIC_LIKE | METRIC_COMMENT;
            case LIKE_COMMENT_FOLLOW -> METRIC_ALL;
        };
    }

    /**
     * Metric bitmask for a raw service category string, for serialization keying. Prefers the exact
     * {@link #fromServiceCategory} mapping; for a category exact-match doesn't recognize (e.g.
     * custom-comment variants) it falls back to OR-ing every metric substring it finds — so a
     * multi-metric name is fully captured, unlike {@code InstagramService.determineStartCount}'s
     * first-match-wins single value — and finally to {@link #METRIC_ALL} (conservative) so an
     * unrecognized order serializes against everything rather than nothing.
     */
    public static int metricMaskForCategory(String category) {
        if (category == null || category.isBlank()) {
            return METRIC_ALL;
        }
        try {
            return fromServiceCategory(category).metricMask();
        } catch (RuntimeException unknownExactCategory) {
            String c = category.toUpperCase();
            int mask = 0;
            if (c.contains("LIKE")) mask |= METRIC_LIKE;
            if (c.contains("COMMENT")) mask |= METRIC_COMMENT;
            if (c.contains("FOLLOW")) mask |= METRIC_FOLLOW;
            return mask == 0 ? METRIC_ALL : mask;
        }
    }

    /** Convert service category name to Instagram order type. */
    public static InstagramOrderType fromServiceCategory(String category) {
        if (category == null) {
            throw new IllegalArgumentException("Category cannot be null");
        }

        return switch (category.toUpperCase()) {
            case "INSTAGRAM_LIKES", "INSTAGRAM LIKES", "INSTAGRAM_MIX_GEO_LIKES" -> LIKE;
            case "INSTAGRAM_FOLLOWS",
                            "INSTAGRAM FOLLOWS",
                            "INSTAGRAM_FOLLOWERS",
                            "INSTAGRAM FOLLOWERS",
                            "INSTAGRAM_MIX_GEO_FOLLOWERS" ->
                    FOLLOW;
            case "INSTAGRAM_COMMENTS", "INSTAGRAM COMMENTS" -> COMMENT;
            case "INSTAGRAM_LIKE_FOLLOW", "INSTAGRAM LIKE+FOLLOW" -> LIKE_FOLLOW;
            case "INSTAGRAM_LIKE_COMMENT", "INSTAGRAM LIKE+COMMENT" -> LIKE_COMMENT;
            case "INSTAGRAM_LIKE_COMMENT_FOLLOW", "INSTAGRAM LIKE+COMMENT+FOLLOW" ->
                    LIKE_COMMENT_FOLLOW;
            default ->
                    throw new IllegalArgumentException(
                            "Unknown Instagram service category: " + category);
        };
    }
}
