package com.decisionrail.events;

import java.time.Instant;

/** A consumer group's own record that it processed one event. */
public record ConsumptionRecordView(String consumerGroup, Instant consumedAt) {}
