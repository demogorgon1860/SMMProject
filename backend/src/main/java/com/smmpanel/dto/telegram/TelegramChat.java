package com.smmpanel.dto.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class TelegramChat {

    @JsonProperty("id")
    private Long id;
}
