package com.smmpanel.dto.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class TelegramUpdate {

    @JsonProperty("update_id")
    private Long updateId;

    @JsonProperty("message")
    private TelegramMessage message;

    @JsonProperty("callback_query")
    private TelegramCallbackQuery callbackQuery;
}
