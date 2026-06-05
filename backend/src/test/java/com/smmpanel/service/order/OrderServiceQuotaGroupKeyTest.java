package com.smmpanel.service.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrderService#quotaGroupKey(String)} — the function that collapses a service
 * name into its action+gender quota group by stripping the trailing {@code [geo]} bracket. Geo
 * variants and the duplicated id-space must map to the same key; genders must stay distinct. Pure
 * function, no Spring context. Must stay in sync with the LIKE pattern in {@code
 * ServiceRepository.findActiveQuotaGroupServiceIds}.
 */
class OrderServiceQuotaGroupKeyTest {

    @Test
    @DisplayName("Geo variants of the same action+gender collapse to one key")
    void geoVariantsShareKey() {
        assertEquals(
                "Instagram Followers [Mix Gender]",
                OrderService.quotaGroupKey("Instagram Followers [Mix Gender] [USA/Europe]"));
        assertEquals(
                "Instagram Followers [Mix Gender]",
                OrderService.quotaGroupKey("Instagram Followers [Mix Gender] [Germany]"));
    }

    @Test
    @DisplayName("Different genders produce different keys")
    void gendersStayDistinct() {
        assertEquals(
                "Instagram Likes [Male]",
                OrderService.quotaGroupKey("Instagram Likes [Male] [USA/Europe]"));
        assertEquals(
                "Instagram Likes [Female]",
                OrderService.quotaGroupKey("Instagram Likes [Female] [USA/Europe]"));
        assertEquals(
                "Instagram Likes [Mix Gender]",
                OrderService.quotaGroupKey("Instagram Likes [Mix Gender] [USA/Europe]"));
    }

    @Test
    @DisplayName("Custom Comments action is kept separate from Comments")
    void actionStaysDistinct() {
        assertEquals(
                "Instagram Custom Comments [FEMALE]",
                OrderService.quotaGroupKey("Instagram Custom Comments [FEMALE] [Germany]"));
        assertEquals(
                "Instagram Comments [FEMALE]",
                OrderService.quotaGroupKey("Instagram Comments [FEMALE] [Germany]"));
    }

    @Test
    @DisplayName("Names without a trailing geo bracket are returned unchanged (own group)")
    void noBracketUnchanged() {
        assertEquals(
                "Instagram MIX GEO Followers",
                OrderService.quotaGroupKey("Instagram MIX GEO Followers"));
    }

    @Test
    @DisplayName("Null name yields an empty key")
    void nullSafe() {
        assertEquals("", OrderService.quotaGroupKey(null));
    }
}
