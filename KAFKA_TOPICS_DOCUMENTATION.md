# Kafka Topics Documentation - SMM Panel

## Overview
The SMM Panel uses Apache Kafka for asynchronous message processing, event sourcing, and microservices communication. Below is a comprehensive list of all Kafka topics and their purposes.

## System Topics (Kafka Internal)

### `__consumer_offsets`
- **Purpose**: Kafka internal topic for storing consumer group offsets
- **Type**: System topic
- **Usage**: Automatically managed by Kafka

### `__transaction_state`
- **Purpose**: Kafka internal topic for transaction coordination
- **Type**: System topic
- **Usage**: Manages transactional message processing

## Order Processing Topics

### `smm.order.processing`
- **Purpose**: Main topic for new order processing
- **Producer**: OrderService (when creating new orders)
- **Consumer**: OrderEventConsumer
- **Message Type**: OrderCreatedEvent
- **Processing**:
  - YouTube verification
  - Initial status update to ACTIVE
  - Start count capture
- **Retry Strategy**: 3 attempts with exponential backoff
- **Partitions**: 3

### `smm.order.processing-retry-0`
- **Purpose**: First retry topic for failed order processing
- **Delay**: 1 second
- **Auto-created**: Yes

### `smm.order.processing-retry-1`
- **Purpose**: Second retry topic for failed order processing
- **Delay**: 2 seconds (exponential backoff)
- **Auto-created**: Yes

### `smm.order.processing-dlt`
- **Purpose**: Dead Letter Topic for permanently failed order processing
- **When Used**: After all retries exhausted
- **Manual Intervention**: Required

### `smm.order.processing.dlq`
- **Purpose**: Dead Letter Queue for order processing failures
- **Current Messages**: 0 (healthy state)

## Order State Management Topics

### `smm.order.state.updates`
- **Purpose**: Tracks order status changes throughout lifecycle
- **Producer**: OrderService, OrderStateManagementService
- **Consumer**: OrderEventConsumer
- **Message Type**: OrderStatusChangedEvent
- **States Tracked**:
  - PENDING → ACTIVE
  - ACTIVE → PROCESSING
  - PROCESSING → COMPLETED/CANCELLED
- **Partitions**: 3

### `smm.order.state.updates-retry-1000`
- **Purpose**: First retry for state update failures
- **Delay**: 1000ms

### `smm.order.state.updates-retry-2000`
- **Purpose**: Second retry for state update failures
- **Delay**: 2000ms

### `smm.order.state.updates-dlt`
- **Purpose**: Dead Letter Topic for state update failures
- **Manual Review**: Required for consistency

### `smm.order.state.updates.dlq`
- **Purpose**: Dead Letter Queue for state updates
- **Current Messages**: 0

### `smm.order.status.updates`
- **Purpose**: Real-time order status notifications
- **Consumer**: WebSocket notification service
- **Use Case**: UI updates, customer notifications

### `smm.order.progress`
- **Purpose**: Tracks order fulfillment progress
- **Data**: Progress percentage, delivered quantity
- **Consumer**: Progress tracking service

### `smm.order.progress.dlq`
- **Purpose**: Failed progress updates
- **Current Messages**: 0

### `smm.order.refund`
- **Purpose**: Order refund processing
- **Producer**: OrderService (on cancellation/refund)
- **Consumer**: RefundProcessingService
- **Processing**: Balance credits, audit logging

### `smm.order.refund.dlq`
- **Purpose**: Failed refund processing
- **Critical**: Yes (financial impact)

## Payment Topics

### `smm.payment.confirmations`
- **Purpose**: Payment confirmation processing
- **Producer**: WebhookController (Cryptomus webhooks)
- **Consumer**: PaymentConfirmationConsumer
- **Processing**:
  - Balance updates
  - Order activation
  - Transaction recording
- **Partitions**: 3

### `smm.payment.confirmations.dlq`
- **Purpose**: Failed payment confirmations
- **Critical**: Yes (revenue impact)

### `smm.payment.refunds`
- **Purpose**: Payment refund processing
- **Producer**: RefundService
- **Consumer**: PaymentRefundConsumer
- **Processing**: Refund to payment provider

### `smm.payment.refunds.dlq`
- **Purpose**: Failed refund processing

### `smm.payment.webhooks`
- **Purpose**: Raw webhook data from payment providers
- **Producer**: WebhookController
- **Consumer**: WebhookProcessingService
- **Partitions**: 3

### `smm.payment.webhooks.dlq`
- **Purpose**: Failed webhook processing

## Video Processing Topics

### `smm.video.processing`
- **Purpose**: YouTube video processing tasks
- **Producer**: OrderService
- **Consumer**: VideoProcessingConsumerService
- **Message Type**: VideoProcessingMessage
- **Processing**:
  - Video clip generation
  - YouTube automation
  - View delivery
- **Partitions**: 2

### `smm.video.processing.retry`
- **Purpose**: Retry for video processing failures
- **Strategy**: Manual retry with backoff

### `smm.video.processing.dlq`
- **Purpose**: Failed video processing tasks
- **Current Messages**: 0

### `smm.video.processing.retry.dlq`
- **Purpose**: Failed retry attempts

### `smm.youtube.processing`
- **Purpose**: YouTube-specific processing
- **Processing**:
  - API quota management
  - View count verification
  - Channel operations

### `smm.youtube.processing.dlq`
- **Purpose**: Failed YouTube operations

### `video.processing.queue`
- **Purpose**: Legacy video processing queue
- **Status**: Deprecated (migrate to smm.video.processing)

### `video.processing.queue.dlq`
- **Purpose**: Legacy DLQ

### `video.processing.dlq`
- **Purpose**: Legacy DLQ

## Binom Integration Topics

### `smm.binom.campaign.creation`
- **Purpose**: Create campaigns in Binom tracker
- **Producer**: BinomService
- **Consumer**: BinomCampaignConsumer
- **Processing**:
  - Campaign setup
  - Offer assignment
  - Traffic distribution

### `smm.binom.campaign.creation.dlq`
- **Purpose**: Failed Binom campaign creation

## Offer Management Topics

### `smm.offer.assignments`
- **Purpose**: Offer assignment to orders
- **Producer**: OfferAssignmentService
- **Consumer**: OfferEventConsumer
- **Processing**:
  - Binom offer mapping
  - Campaign association
- **Partitions**: 3

### `smm.offer.assignment.events`
- **Purpose**: Offer assignment event stream
- **Use Case**: Audit trail, analytics

### `smm.offer.assignments.dlq`
- **Purpose**: Failed offer assignments

## Notification Topics

### `smm.notifications`
- **Purpose**: General notification processing
- **Types**:
  - Email notifications
  - SMS alerts
  - Push notifications
- **Consumer**: NotificationService

### `smm.notifications.dlq`
- **Purpose**: Failed notifications

## Topic Health Status

| Topic Category | Active Topics | DLQ Messages | Status |
|---------------|--------------|--------------|--------|
| Order Processing | 14 | 0 | ✅ Healthy |
| Payment | 6 | 0 | ✅ Healthy |
| Video Processing | 7 | 0 | ✅ Healthy |
| Binom Integration | 2 | 0 | ✅ Healthy |
| Offer Management | 3 | 0 | ✅ Healthy |
| Notifications | 2 | 0 | ✅ Healthy |

## Consumer Groups

### Active Consumer Groups:
1. **smm-order-processing-group**: Processes new orders
2. **order-status-group**: Handles status changes
3. **smm-payment-confirmations-realtime-group**: Real-time payment processing
4. **smm-payment-refunds-group**: Refund processing
5. **video-processing-group**: Video/YouTube processing
6. **offer-assignment-group**: Offer assignments
7. **websocket-notification-group**: WebSocket updates
8. **dlq-processing-group**: DLQ message recovery
9. **cqrs-read-model-group**: CQRS read model updates

## Message Flow Patterns

### Order Creation Flow:
```
OrderService
  → smm.order.processing (OrderCreatedEvent)
    → OrderEventConsumer
      → smm.order.state.updates (StatusChangedEvent)
        → Status tracking & notifications
```

### Payment Flow:
```
WebhookController
  → smm.payment.webhooks (Raw webhook)
    → smm.payment.confirmations (Parsed payment)
      → PaymentConfirmationConsumer
        → Balance update & Order activation
```

### Video Processing Flow:
```
OrderService
  → smm.video.processing (VideoProcessingMessage)
    → VideoProcessingConsumerService
      → YouTube API operations
      → Progress updates to smm.order.progress
```

## Retry Strategy

### Standard Retry Pattern:
1. **Initial attempt** on main topic
2. **Retry 0**: 1 second delay
3. **Retry 1**: 2 second delay (exponential)
4. **Retry 2**: 4 second delay
5. **DLT**: After all retries exhausted

### Financial Topics (Enhanced):
- Payment confirmations: 5 retries with longer backoff
- Refunds: Manual review after 3 attempts

## Configuration

### Default Settings:
- **Auto-create topics**: Enabled
- **Replication factor**: 1 (development)
- **Min in-sync replicas**: 1
- **Retention**: 7 days (168 hours)
- **Compression**: snappy

### Performance Tuning:
- **Batch size**: 16KB
- **Linger ms**: 100
- **Buffer memory**: 32MB
- **Max request size**: 1MB

## Monitoring & Alerts

### Key Metrics:
1. **Consumer lag**: Should be < 1000 messages
2. **DLQ accumulation**: Alert if > 0
3. **Processing time**: < 500ms average
4. **Error rate**: < 1%

### Health Checks:
- Consumer group status: STABLE or EMPTY
- Partition assignment: All partitions assigned
- Connection pool: Active connections present
- Producer readiness: Can send test messages

## Best Practices

1. **Idempotency**: All consumers use message deduplication
2. **Transactionality**: Database operations wrapped in transactions
3. **Error Handling**: Structured retry with exponential backoff
4. **Monitoring**: All topics have corresponding DLQ
5. **Traceability**: Correlation IDs for request tracking
6. **Performance**: Batch processing where applicable
7. **Security**: No sensitive data in message keys

## Troubleshooting

### Common Issues:

1. **Consumer Lag**:
   - Check: `kafka-consumer-groups --describe --group <group>`
   - Fix: Scale consumers or optimize processing

2. **DLQ Messages**:
   - Check: `kafka-console-consumer --topic <topic>.dlq`
   - Fix: Manual review and reprocessing

3. **Partition Imbalance**:
   - Check: Topic partition distribution
   - Fix: Rebalance or add partitions

4. **Connection Issues**:
   - Check: Network connectivity
   - Fix: Verify Kafka broker addresses

## Maintenance

### Regular Tasks:
- Monitor DLQ topics daily
- Review consumer lag metrics
- Clean up old messages (based on retention)
- Verify consumer group health
- Check for orphaned topics

### Cleanup Commands:
```bash
# Delete old messages
kafka-delete-records --topic <topic> --offset-json-file delete.json

# Reset consumer group
kafka-consumer-groups --reset-offsets --group <group> --topic <topic> --to-earliest

# Delete unused topic
kafka-topics --delete --topic <topic>
```