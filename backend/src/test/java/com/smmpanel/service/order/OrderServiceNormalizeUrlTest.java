package com.smmpanel.service.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden-case tests for {@link OrderService#normalizeInstagramUrl(String)} — the canonical form the
 * per-URL quota keys on. This MUST stay in sync with the SQL backfill in {@code
 * v2026.06-canonicalize-instagram-links.xml}; every case here has a matching transform there.
 * Pure function, no Spring context.
 */
class OrderServiceNormalizeUrlTest {

    private static final String CANONICAL_PROFILE = "https://www.instagram.com/chrimbu/";

    @Test
    @DisplayName("All variant spellings of one profile collapse to a single canonical form")
    void collapsesProfileVariants() {
        assertEquals(CANONICAL_PROFILE, OrderService.normalizeInstagramUrl("Chrimbu"));
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("https://www.instagram.com/Chrimbu/"));
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("https://instagram.com/Chrimbu/"));
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("https://m.instagram.com/chrimbu"));
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("http://www.instagram.com/CHRIMBU"));
    }

    @Test
    @DisplayName("Post shortcode case is preserved (shortcodes are case-sensitive)")
    void preservesPostShortcodeCase() {
        assertEquals(
                "https://www.instagram.com/p/DZE5GuwAsa1/",
                OrderService.normalizeInstagramUrl("https://www.instagram.com/p/DZE5GuwAsa1/"));
        // host canonicalized, shortcode untouched
        assertEquals(
                "https://www.instagram.com/p/DZE5GuwAsa1/",
                OrderService.normalizeInstagramUrl("https://instagram.com/p/DZE5GuwAsa1"));
    }

    @Test
    @DisplayName("/reel/ and /reels/ are rewritten to /p/")
    void convertsReelToPost() {
        assertEquals(
                "https://www.instagram.com/p/ABCdef01234/",
                OrderService.normalizeInstagramUrl("https://www.instagram.com/reel/ABCdef01234/"));
        assertEquals(
                "https://www.instagram.com/p/ABCdef01234/",
                OrderService.normalizeInstagramUrl("https://www.instagram.com/reels/ABCdef01234/"));
    }

    @Test
    @DisplayName("Leading @ on a profile path is stripped")
    void stripsLeadingAt() {
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("https://www.instagram.com/@Chrimbu/"));
    }

    @Test
    @DisplayName("Query string and fragment are stripped")
    void stripsQueryAndFragment() {
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("https://www.instagram.com/Chrimbu/?igsh=abc123"));
        assertEquals(
                "https://www.instagram.com/p/DZE5GuwAsa1/",
                OrderService.normalizeInstagramUrl(
                        "https://www.instagram.com/p/DZE5GuwAsa1/?img_index=1"));
    }

    @Test
    @DisplayName("Mobile share token after an 11-char shortcode is truncated")
    void truncatesShareToken() {
        assertEquals(
                "https://www.instagram.com/p/DW0O1I1jbSJ/",
                OrderService.normalizeInstagramUrl(
                        "https://www.instagram.com/p/DW0O1I1jbSJONCifAAQ8kaVxavBX"));
    }

    @Test
    @DisplayName("Trailing slash is appended and surrounding whitespace trimmed")
    void addsTrailingSlashAndTrims() {
        assertEquals(
                CANONICAL_PROFILE,
                OrderService.normalizeInstagramUrl("   https://www.instagram.com/chrimbu   "));
    }

    @Test
    @DisplayName("Non-Instagram URLs are returned unchanged")
    void leavesNonInstagramUnchanged() {
        assertEquals(
                "https://youtube.com/watch?v=abc",
                OrderService.normalizeInstagramUrl("https://youtube.com/watch?v=abc"));
    }

    @Test
    @DisplayName("Null and empty inputs are returned as-is")
    void nullAndEmptySafe() {
        assertEquals(null, OrderService.normalizeInstagramUrl(null));
        assertEquals("", OrderService.normalizeInstagramUrl(""));
    }
}
