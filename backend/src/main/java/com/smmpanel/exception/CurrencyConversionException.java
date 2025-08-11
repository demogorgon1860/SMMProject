package com.smmpanel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Exception thrown when currency conversion fails */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CurrencyConversionException extends RuntimeException {

    public CurrencyConversionException(String message) {
        super(message);
    }

    public CurrencyConversionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CurrencyConversionException(String fromCurrency, String toCurrency) {
        super(String.format("Failed to convert from %s to %s", fromCurrency, toCurrency));
    }

    public CurrencyConversionException(String fromCurrency, String toCurrency, Throwable cause) {
        super(String.format("Failed to convert from %s to %s", fromCurrency, toCurrency), cause);
    }
}
