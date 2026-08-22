package com.smmpanel.service.order;

import static com.smmpanel.service.order.OrderService.isPostUrl;
import static com.smmpanel.service.order.OrderService.isProfileUrl;
import static com.smmpanel.service.order.OrderService.normalizeInstagramUrl;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Link-shape classification used by the per-service link rule: LIKE/COMMENT orders require a
 * post/reel link ({@link OrderService#isPostUrl}); FOLLOW orders require a profile link ({@link
 * OrderService#isProfileUrl}). Both operate on {@link OrderService#normalizeInstagramUrl} output, so
 * every case normalizes first, exactly as the order-create path does.
 */
class OrderServiceLinkShapeTest {

    private static String norm(String raw) {
        return normalizeInstagramUrl(raw);
    }

    // ---------------- isPostUrl (likes / comments) ----------------

    @Test
    @DisplayName("post shortcode, reel, reels and IGTV all classify as a post")
    void postForms_arePosts() {
        assertTrue(isPostUrl(norm("https://www.instagram.com/p/DW0O1I1jbSJ/")));
        assertTrue(isPostUrl(norm("https://instagram.com/reel/DW0O1I1jbSJ/"))); // reel → /p/
        assertTrue(isPostUrl(norm("https://www.instagram.com/reels/DW0O1I1jbSJ/"))); // reels → /p/
        assertTrue(isPostUrl(norm("https://www.instagram.com/tv/ABCdef12345/"))); // IGTV
        assertTrue(isPostUrl(norm("instagram.com/p/DW0O1I1jbSJ/?igsh=xyz"))); // query stripped
        assertTrue(isPostUrl(norm("https://www.instagram.com/p/DW0O1I1jbSJ/#comments"))); // fragment
    }

    @Test
    @DisplayName("a profile link is NOT a post")
    void profile_isNotPost() {
        assertFalse(isPostUrl(norm("https://www.instagram.com/nike/")));
        assertFalse(isPostUrl(norm("nike"))); // bare handle → profile
        assertFalse(isPostUrl(norm("@nike")));
    }

    @Test
    @DisplayName("non-content sections (stories/explore/tagged) are not posts")
    void sections_areNotPosts() {
        assertFalse(isPostUrl(norm("https://www.instagram.com/stories/nike/123/")));
        assertFalse(isPostUrl(norm("https://www.instagram.com/explore/tags/x/")));
    }

    // ---------------- isProfileUrl (followers) ----------------

    @Test
    @DisplayName("a single non-reserved segment is a profile (handle, @handle, full URL)")
    void handleForms_areProfiles() {
        assertTrue(isProfileUrl(norm("nike")));
        assertTrue(isProfileUrl(norm("@nike")));
        assertTrue(isProfileUrl(norm("https://www.instagram.com/nike/")));
        assertTrue(isProfileUrl(norm("instagram.com/Nike"))); // case-folded to /nike/
        assertTrue(isProfileUrl(norm("https://m.instagram.com/nike?hl=en"))); // host + query normalized
    }

    @Test
    @DisplayName("a post/reel link is NOT a profile")
    void post_isNotProfile() {
        assertFalse(isProfileUrl(norm("https://www.instagram.com/p/DW0O1I1jbSJ/")));
        assertFalse(isProfileUrl(norm("https://www.instagram.com/reel/DW0O1I1jbSJ/")));
        assertFalse(isProfileUrl(norm("https://www.instagram.com/tv/ABCdef12345/")));
    }

    @Test
    @DisplayName("a profile sub-tab or reserved section is NOT a clean profile")
    void multiSegmentOrReserved_isNotProfile() {
        assertFalse(isProfileUrl(norm("https://www.instagram.com/nike/tagged/")));
        assertFalse(isProfileUrl(norm("https://www.instagram.com/stories/nike/")));
        assertFalse(isProfileUrl(norm("https://www.instagram.com/explore/"))); // reserved segment
        assertFalse(isProfileUrl(norm("https://www.instagram.com/s/AbCdEf/"))); // /s/ share prefix
        assertFalse(isPostUrl(norm("https://www.instagram.com/s/AbCdEf/"))); // …and not a post
    }

    // ---------------- mutual exclusion + garbage ----------------

    @Test
    @DisplayName("a URL is never both a post and a profile; junk/pathless is neither")
    void mutualExclusionAndJunk() {
        String post = norm("https://www.instagram.com/p/DW0O1I1jbSJ/");
        String profile = norm("nike");
        assertTrue(isPostUrl(post) && !isProfileUrl(post));
        assertTrue(isProfileUrl(profile) && !isPostUrl(profile));

        // Pathless host / non-instagram / null are neither shape.
        assertFalse(isPostUrl("https://www.instagram.com/"));
        assertFalse(isProfileUrl("https://www.instagram.com/"));
        assertFalse(isPostUrl(null));
        assertFalse(isProfileUrl(null));
    }
}
