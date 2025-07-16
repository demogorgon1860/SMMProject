package com.smmpanel.entity;

public enum OrderStatus {
    PENDING("Pending"),           // Заказ создан, ожидает обработки
    IN_PROGRESS("In progress"),   // Заказ в процессе обработки
    PROCESSING("Processing"),     // Создание клипа/настройка кампании
    ACTIVE("In progress"),        // Льётся трафик, накрутка в процессе
    PARTIAL("Partial"),           // Частично выполнен
    COMPLETED("Completed"),       // Цель достигнута
    CANCELLED("Canceled"),        // Заказ отменён
    PAUSED("Paused"),            // Накрутка приостановлена
    HOLDING("In progress"),       // Режим удержания и мониторинга
    REFILL("Refill");            // Требуется доливка
    
    private final String displayName;
    
    OrderStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}