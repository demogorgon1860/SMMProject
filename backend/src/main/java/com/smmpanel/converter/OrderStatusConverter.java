package com.smmpanel.converter;

import com.smmpanel.entity.OrderStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.sql.SQLException;
import org.postgresql.util.PGobject;

@Converter(autoApply = false)
public class OrderStatusConverter implements AttributeConverter<OrderStatus, Object> {

    @Override
    public Object convertToDatabaseColumn(OrderStatus attribute) {
        if (attribute == null) {
            return null;
        }

        PGobject pgObject = new PGobject();
        try {
            pgObject.setType("order_status");
            pgObject.setValue(attribute.name());
        } catch (SQLException e) {
            throw new IllegalArgumentException(
                    "Error converting OrderStatus to database column", e);
        }
        return pgObject;
    }

    @Override
    public OrderStatus convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }

        String value;
        if (dbData instanceof PGobject) {
            value = ((PGobject) dbData).getValue();
        } else {
            value = dbData.toString();
        }

        try {
            return OrderStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown order_status value: " + value, e);
        }
    }
}
