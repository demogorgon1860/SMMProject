package com.smmpanel.dto.instagram;

/**
 * Supported Instagram order types for the bot.
 */
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

    /**
     * Convert service category name to Instagram order type.
     */
    public static InstagramOrderType fromServiceCategory(String category) {
        if (category == null) {
            throw new IllegalArgumentException("Category cannot be null");
        }

        return switch (category.toUpperCase()) {
            case "INSTAGRAM_LIKES", "INSTAGRAM LIKES" -> LIKE;
            case "INSTAGRAM_FOLLOWS", "INSTAGRAM FOLLOWS", "INSTAGRAM_FOLLOWERS", "INSTAGRAM FOLLOWERS" -> FOLLOW;
            case "INSTAGRAM_COMMENTS", "INSTAGRAM COMMENTS" -> COMMENT;
            case "INSTAGRAM_LIKE_FOLLOW", "INSTAGRAM LIKE+FOLLOW" -> LIKE_FOLLOW;
            case "INSTAGRAM_LIKE_COMMENT", "INSTAGRAM LIKE+COMMENT" -> LIKE_COMMENT;
            case "INSTAGRAM_LIKE_COMMENT_FOLLOW", "INSTAGRAM LIKE+COMMENT+FOLLOW" -> LIKE_COMMENT_FOLLOW;
            default -> throw new IllegalArgumentException("Unknown Instagram service category: " + category);
        };
    }
}
