package com.smmpanel.converter;

import com.smmpanel.entity.PaymentStatus;
import jakarta.persistence.Converter;

/**
 * JPA converter for PaymentStatus enum to PostgreSQL payment_status type. Handles the conversion
 * between Java PaymentStatus enum and PostgreSQL enum type.
 */
@Converter(autoApply = false)
public class PaymentStatusConverter extends PostgreSQLEnumConverter<PaymentStatus> {

    public PaymentStatusConverter() {
        super(PaymentStatus.class, "payment_status");
    }
}
