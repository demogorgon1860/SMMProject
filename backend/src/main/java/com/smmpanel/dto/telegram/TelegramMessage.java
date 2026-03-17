package com.smmpanel.dto.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class TelegramMessage {

    @JsonProperty("message_id")
    private Integer messageId;

    @JsonProperty("chat")
    private TelegramChat chat;

    @JsonProperty("text")
    private String text;
}
