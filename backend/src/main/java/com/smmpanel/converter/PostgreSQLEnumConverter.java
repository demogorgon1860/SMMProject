package com.smmpanel.converter;

import jakarta.persistence.AttributeConverter;
import org.postgresql.util.PGobject;

/**
 * Generic PostgreSQL enum converter for Hibernate. Handles conversion between Java enums and
 * PostgreSQL enum types.
 *
 * <p>This abstract class can be extended for any enum type that needs to be stored as a PostgreSQL
 * enum in the database.
 *
 * @param <T> The Java enum type
 */
public abstract class PostgreSQLEnumConverter<T extends Enum<T>>
        implements AttributeConverter<T, Object> {

    private final Class<T> enumClass;
    private final String postgresType;

    /**
     * Constructor for the converter
     *
     * @param enumClass The Java enum class
     * @param postgresType The PostgreSQL enum type name (e.g., "user_role", "order_status")
     */
    protected PostgreSQLEnumConverter(Class<T> enumClass, String postgresType) {
        this.enumClass = enumClass;
        this.postgresType = postgresType;
    }

    @Override
    public Object convertToDatabaseColumn(T attribute) {
        if (attribute == null) {
            return null;
        }

        try {
            // Use PGobject to properly handle PostgreSQL enum types
            PGobject pgObject = new PGobject();
            pgObject.setType(postgresType);
            pgObject.setValue(attribute.name());
            return pgObject;
        } catch (Exception e) {
            // Fallback to string if PGobject fails
            return attribute.name();
        }
    }

    @Override
    public T convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }

        String value = null;
        if (dbData instanceof PGobject) {
            value = ((PGobject) dbData).getValue();
        } else if (dbData instanceof String) {
            value = (String) dbData;
        } else {
            value = dbData.toString();
        }

        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    String.format("Unknown %s value: %s", postgresType, value), e);
        }
    }
}
