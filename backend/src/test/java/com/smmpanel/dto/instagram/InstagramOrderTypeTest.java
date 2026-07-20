package com.smmpanel.dto.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the metric-mask mapping used by metric-aware per-URL order serialization. */
class InstagramOrderTypeTest {

    @Test
    void metricMaskPerType() {
        assertThat(InstagramOrderType.LIKE.metricMask()).isEqualTo(1);
        assertThat(InstagramOrderType.COMMENT.metricMask()).isEqualTo(2);
        assertThat(InstagramOrderType.FOLLOW.metricMask()).isEqualTo(4);
        assertThat(InstagramOrderType.LIKE_FOLLOW.metricMask()).isEqualTo(1 | 4);
        assertThat(InstagramOrderType.LIKE_COMMENT.metricMask()).isEqualTo(1 | 2);
        assertThat(InstagramOrderType.LIKE_COMMENT_FOLLOW.metricMask())
                .isEqualTo(InstagramOrderType.METRIC_ALL)
                .isEqualTo(7);
    }

    @Test
    void metricMaskForCategoryExactMatch() {
        assertThat(InstagramOrderType.metricMaskForCategory("INSTAGRAM_LIKES")).isEqualTo(1);
        assertThat(InstagramOrderType.metricMaskForCategory("INSTAGRAM_COMMENTS")).isEqualTo(2);
        assertThat(InstagramOrderType.metricMaskForCategory("INSTAGRAM_FOLLOWERS")).isEqualTo(4);
        assertThat(InstagramOrderType.metricMaskForCategory("INSTAGRAM_LIKE_COMMENT")).isEqualTo(3);
    }

    @Test
    void metricMaskForCategorySubstringFallbackForUnknownExactCategory() {
        // Not an exact enum category → falls back to substring detection (e.g. custom-comment).
        assertThat(InstagramOrderType.metricMaskForCategory("INSTAGRAM_CUSTOM_COMMENTS"))
                .isEqualTo(InstagramOrderType.METRIC_COMMENT);
        assertThat(InstagramOrderType.metricMaskForCategory("SOME_LIKE_AND_FOLLOW_BUNDLE"))
                .isEqualTo(InstagramOrderType.METRIC_LIKE | InstagramOrderType.METRIC_FOLLOW);
    }

    @Test
    void metricMaskForCategoryUnknownOrBlankIsConservativeAll() {
        assertThat(InstagramOrderType.metricMaskForCategory(null))
                .isEqualTo(InstagramOrderType.METRIC_ALL);
        assertThat(InstagramOrderType.metricMaskForCategory("  "))
                .isEqualTo(InstagramOrderType.METRIC_ALL);
        assertThat(InstagramOrderType.metricMaskForCategory("YOUTUBE_VIEWS"))
                .isEqualTo(InstagramOrderType.METRIC_ALL);
    }
}
