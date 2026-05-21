package com.smmpanel.service.instagram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the gender-targeting helpers in {@link InstagramRabbitPublisher}.
 *
 * <p>Both helpers are pure functions of their inputs (no Spring context, no I/O), so a plain
 * JUnit class is enough — these tests are mostly there to lock the bot-side contract in
 * place. The bot reads gender ONLY from the {@code GENDER:MALE\n} / {@code GENDER:FEMALE\n}
 * prefix at the start of {@code commentText}; if these tests start failing, the very real
 * customer-facing symptom ("MALE-targeted order delivered by female accounts") comes back.
 */
class InstagramRabbitPublisherGenderTest {

    @Nested
    @DisplayName("parseGenderFromServiceName")
    class ParseGenderTests {

        @Test
        @DisplayName("MALE in brackets → MALE")
        void maleMarker() {
            assertEquals(
                    "MALE",
                    InstagramRabbitPublisher.parseGenderFromServiceName(
                            "Instagram Custom Comments [MALE] [USA/Europe]"));
            assertEquals(
                    "MALE",
                    InstagramRabbitPublisher.parseGenderFromServiceName(
                            "Instagram Likes [Male] [Germany]"));
        }

        @Test
        @DisplayName("FEMALE in brackets → FEMALE (must not match MALE inside FEMALE)")
        void femaleMarker() {
            assertEquals(
                    "FEMALE",
                    InstagramRabbitPublisher.parseGenderFromServiceName(
                            "Instagram Custom Comments [FEMALE] [USA/Europe]"));
            assertEquals(
                    "FEMALE",
                    InstagramRabbitPublisher.parseGenderFromServiceName(
                            "Instagram Followers [Female] [Germany]"));
        }

        @Test
        @DisplayName("Mix Gender / no marker → null (bot defaults to mixed pool)")
        void mixGender() {
            assertNull(
                    InstagramRabbitPublisher.parseGenderFromServiceName(
                            "Instagram Custom Comments [Mix Gender] [USA/Europe]"));
            assertNull(
                    InstagramRabbitPublisher.parseGenderFromServiceName(
                            "Instagram MIX GEO Followers"));
            assertNull(InstagramRabbitPublisher.parseGenderFromServiceName(""));
        }

        @Test
        @DisplayName("null / weird input → null (no NPE, no surprise prefix)")
        void nullSafe() {
            assertNull(InstagramRabbitPublisher.parseGenderFromServiceName(null));
            assertNull(InstagramRabbitPublisher.parseGenderFromServiceName("totally unrelated"));
        }
    }

    @Nested
    @DisplayName("buildCommentTextWithGender")
    class BuildCommentTextTests {

        @Test
        @DisplayName("Mix Gender (null) + custom text → text unchanged")
        void mixGenderUntouched() {
            assertEquals(
                    "first comment\nsecond comment",
                    InstagramRabbitPublisher.buildCommentTextWithGender(
                            "first comment\nsecond comment", null));
            assertNull(InstagramRabbitPublisher.buildCommentTextWithGender(null, null));
        }

        @Test
        @DisplayName("Custom Comments [MALE]: prefix prepended before user text")
        void maleWithComments() {
            assertEquals(
                    "GENDER:MALE\nfirst comment\nsecond comment",
                    InstagramRabbitPublisher.buildCommentTextWithGender(
                            "first comment\nsecond comment", "MALE"));
        }

        @Test
        @DisplayName("Likes/Followers [MALE]: empty payload → just the prefix line")
        void maleNoComments() {
            assertEquals(
                    "GENDER:MALE\n",
                    InstagramRabbitPublisher.buildCommentTextWithGender(null, "MALE"));
            assertEquals(
                    "GENDER:MALE\n", InstagramRabbitPublisher.buildCommentTextWithGender("", "MALE"));
        }

        @Test
        @DisplayName("FEMALE prefix prepended just like MALE")
        void femalePrefix() {
            assertEquals(
                    "GENDER:FEMALE\n",
                    InstagramRabbitPublisher.buildCommentTextWithGender(null, "FEMALE"));
            assertEquals(
                    "GENDER:FEMALE\nhi",
                    InstagramRabbitPublisher.buildCommentTextWithGender("hi", "FEMALE"));
        }

        @Test
        @DisplayName("Existing GENDER:X prefix not stacked (idempotent)")
        void idempotent() {
            assertEquals(
                    "GENDER:MALE\nhi",
                    InstagramRabbitPublisher.buildCommentTextWithGender("GENDER:MALE\nhi", "MALE"));
            // Even if the caller passes the wrong gender, an already-prefixed payload wins.
            assertEquals(
                    "GENDER:FEMALE\nhi",
                    InstagramRabbitPublisher.buildCommentTextWithGender(
                            "GENDER:FEMALE\nhi", "MALE"));
        }
    }
}
