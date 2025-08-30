#!/bin/bash

# Kafka Topic Creation Script for SMM Panel
# This script creates all required Kafka topics with proper configuration
# Usage: ./create-kafka-topics.sh [kafka-bootstrap-server]

KAFKA_BOOTSTRAP_SERVER=${1:-localhost:9092}

echo "Creating Kafka topics on $KAFKA_BOOTSTRAP_SERVER..."

# Function to create topic with error handling
create_topic() {
    local TOPIC_NAME=$1
    local PARTITIONS=$2
    local REPLICATION_FACTOR=$3
    shift 3
    local CONFIGS="$@"
    
    echo "Creating topic: $TOPIC_NAME"
    kafka-topics --create --if-not-exists \
        --bootstrap-server $KAFKA_BOOTSTRAP_SERVER \
        --topic $TOPIC_NAME \
        --partitions $PARTITIONS \
        --replication-factor $REPLICATION_FACTOR \
        $CONFIGS
    
    if [ $? -eq 0 ]; then
        echo "✓ Topic $TOPIC_NAME created successfully"
    else
        echo "✗ Failed to create topic $TOPIC_NAME"
    fi
}

# Order processing topics
create_topic "smm.order.processing" 3 1 \
    "--config retention.ms=604800000 --config compression.type=snappy"

create_topic "smm.order.state.updates" 3 1 \
    "--config retention.ms=604800000 --config compression.type=snappy"

create_topic "smm.order.refund" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

# Video processing topics
create_topic "smm.video.processing" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.video.processing.retry" 1 1 \
    "--config retention.ms=86400000 --config compression.type=snappy"

create_topic "smm.youtube.processing" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

# Binom and offer assignment topics
create_topic "smm.binom.campaign.creation" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.offer.assignments" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.offer.assignment.events" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

# Notification topic
create_topic "smm.notifications" 3 1 \
    "--config retention.ms=604800000 --config compression.type=snappy"

# Payment topics (critical - with min.insync.replicas)
create_topic "smm.payment.confirmations" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy --config min.insync.replicas=1"

create_topic "smm.payment.webhooks" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy --config min.insync.replicas=1"

create_topic "smm.payment.refunds" 3 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy --config min.insync.replicas=1"

# DLQ (Dead Letter Queue) topics
create_topic "smm.order.processing.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.order.state.updates.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.video.processing.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "video.processing.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.video.processing.retry.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.youtube.processing.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.binom.campaign.creation.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.offer.assignments.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.order.refund.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.notifications.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy"

create_topic "smm.payment.confirmations.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy --config min.insync.replicas=1"

create_topic "smm.payment.webhooks.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy --config min.insync.replicas=1"

create_topic "smm.payment.refunds.dlq" 1 1 \
    "--config retention.ms=2592000000 --config compression.type=snappy --config min.insync.replicas=1"

echo ""
echo "Topic creation complete!"
echo ""
echo "To list all topics:"
echo "kafka-topics --list --bootstrap-server $KAFKA_BOOTSTRAP_SERVER"
echo ""
echo "To describe a topic:"
echo "kafka-topics --describe --topic <topic-name> --bootstrap-server $KAFKA_BOOTSTRAP_SERVER"