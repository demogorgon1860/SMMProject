package com.smmpanel.converter;

import com.smmpanel.entity.TransactionType;
import jakarta.persistence.Converter;

/**
 * JPA converter for TransactionType enum to PostgreSQL transaction_type type. Handles the
 * conversion between Java TransactionType enum and PostgreSQL enum type.
 */
@Converter(autoApply = false)
public class TransactionTypeConverter extends PostgreSQLEnumConverter<TransactionType> {

    public TransactionTypeConverter() {
        super(TransactionType.class, "transaction_type");
    }
}
