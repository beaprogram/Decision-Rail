# Observability

What the application emits, what each signal means, and how to follow one synthetic payment through
all of them.

## The three signals and what each is for

| Signal | Answers | Where |
| --- | --- | --- |
| Metrics | Is it slow, is it failing, is the backlog draining | `/actuator/prometheus`, OPERATIONS only |
| Traces | Where did the time go for *this* command, and what happened to its event | OTLP export, off by default |
| Structured logs | What decision was taken, with the identifiers needed to join to the other two | stdout |

They are correlated by identifiers, not by convention: every log record carries `traceId`, `spanId`
and the server-generated `requestId`, and every durable event carries the trace of the command that
produced it.

## Turning it on

Nothing is required to run DecisionRail. Spans are created and their ids reach logs whether or not a
collector exists; exporting only decides whether they also leave the process.

```bash
# Structured JSON logs, one object per line
LOG_FORMAT=ecs java -jar target/decisionrail-0.1.0.jar

# Export traces to a collector
OTLP_EXPORT_ENABLED=true OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces java -jar ...

# Sample a fraction of requests instead of all of them
TRACING_SAMPLE_RATE=0.1 java -jar ...
```

### The optional local stack

Prometheus, Tempo and Grafana, provisioned so there is nothing to click together:

```bash
# The scrape needs the metrics-only identity. This file is generated, not committed.
printf 'username: operations\npassword: %s\n' "$(grep '^OPERATIONS_PASSWORD=' .env | cut -d= -f2-)" \
  > observability/prometheus/scrape-credentials.yml
chmod 600 observability/prometheus/scrape-credentials.yml

GRAFANA_PASSWORD='choose-one' docker compose -f observability/compose.observability.yaml up -d
# Grafana http://127.0.0.1:3000 · Prometheus http://127.0.0.1:9090 · Tempo http://127.0.0.1:3200

docker compose -f observability/compose.observability.yaml down -v   # also drops stored data
rm -f observability/prometheus/scrape-credentials.yml
```

Everything binds to loopback, retention is capped at six hours and 512MB, and Grafana refuses to start
without a password. The dashboard **DecisionRail overview** is provisioned from
`observability/grafana/dashboards/`.

`/actuator/prometheus` stays restricted to the OPERATIONS identity, which has no dashboard workspace
and no financial authority. Scraping it does not change that, and the scrape credentials live outside
source control.

## Metric catalogue

Units are seconds for timers and plain counts otherwise. Counters reset when the process restarts, so
query them as rates. Every timer publishes a histogram: percentiles are computed in the backend from
exported buckets, never averaged from per-instance summaries.

### HTTP

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `http_server_requests_seconds` | timer | `uri` (route template), `method`, `status`, `outcome`, `exception` | Full request, including authentication and password verification. The label is the template, never the raw URL. |

A business decline is a success here. `POST /v1/payments/authorizations` returning 201 with a DECLINED
payment is a correct answer; it appears in the command counters below, not as an HTTP failure.

### Payment commands

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `decisionrail_payments_commands_total` | counter | `operation`, `status`, `risk_outcome`, `funding` | One per committed command. A policy decline (`risk_outcome=DECLINE`) and a funding decline (`funding=INSUFFICIENT_FUNDS`, `risk_outcome=APPROVE`) are different series on purpose. |
| `decisionrail_decision_duration_seconds` | timer | none | Policy evaluation only: no HTTP, no authentication, no database. Comparing it with HTTP latency separates an expensive policy from an expensive request. |
| `decisionrail_idempotency_replayed_total` | counter | none | A key was reused with the same request and the stored result was returned. Expected under retries. |
| `decisionrail_idempotency_conflicts_total` | counter | none | A key was reused with a *different* request. A client defect, not a retry. |

### Delivery

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `decisionrail_outbox_backlog` | gauge | `status` | Rows by delivery status. At most 5s old (see freshness below). |
| `decisionrail_outbox_backlog_age_seconds` | gauge | none | Age of the oldest undelivered event, measured from the `occurred_at` written in the payment transaction. |
| `decisionrail_outbox_blocked_payments` | gauge | none | Payments whose stream is held by a terminally failed event and needs an operator redrive. |
| `decisionrail_outbox_publish_duration_seconds` | timer | `outcome` | One attempt, from offering the record to acknowledgement. Successes and failures are separate series. |
| `decisionrail_outbox_publish_attempts_total` | counter | none | Attempts started. |
| `decisionrail_outbox_publish_acknowledged_total` | counter | none | The broker acknowledged. **Not** the same as completed. |
| `decisionrail_outbox_published_total` | counter | none | Acknowledged *and* recorded under a lease still held. This is completion. |
| `decisionrail_outbox_publish_failures_total` | counter | `retryable` | Attempts that failed. |
| `decisionrail_outbox_publish_short_circuited_total` | counter | none | Not attempted because the breaker was open. |
| `decisionrail_outbox_lease_lost_total` | counter | none | Acknowledged but not recorded: another worker owns the row and a duplicate delivery is expected. |
| `decisionrail_outbox_leases_reclaimed_total` | counter | none | Rows recovered from an expired lease. |
| `decisionrail_broker_breaker_state` | gauge | none | 0 closed, 1 half-open, 2 open. |

### Consumption

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `decisionrail_consumer_processing_duration_seconds` | timer | `group`, `outcome` | Receipt to committed effect, or to quarantine. |
| `decisionrail_consumer_applied_total` | counter | `group` | A new effect committed. |
| `decisionrail_consumer_duplicates_total` | counter | `group` | A redelivery that produced no second effect. Expected under at-least-once delivery. |
| `decisionrail_consumer_out_of_order_total` | counter | `group` | Recorded but not applied: the sequence was not newer than the projection. |
| `decisionrail_consumer_quarantined_total` | counter | `group`, `reason` | Refused records, by bounded reason. |
| `decisionrail_consumer_quarantine_size` | gauge | `group` | Current quarantine depth. |

### Asynchronous workloads and runtime

`decisionrail_replay_jobs{status}`, `decisionrail_replay_items_remaining`,
`decisionrail_shadow_tasks{state}`, `decisionrail_shadow_comparisons_recorded`,
`decisionrail_shadow_comparisons_diverged`, plus the standard `jvm_*` and `hikaricp_*` families Spring
Boot registers.

### Label discipline

No metric is labelled with a payment id, account id, merchant id, trace id, request id, policy version
string, exception message or raw URL. Those grow without limit, and an unbounded label set turns a
metrics backend into an outage of its own. High-cardinality identifiers belong on spans, which are
queried deliberately, and in the operator APIs.

### Gauge freshness and unavailable data

Backlog and queue-depth gauges read one cached aggregate rather than a `count(*)` per label per scrape.
Sixteen full counts every fifteen seconds over tables that only grow made the cost of watching the
system rise with its own history, and it landed hardest when the system was busiest.

- Readings are **at most 5 seconds old**. Alert over a window wider than that.
- If the database cannot answer, the gauge reports **no data**, not zero. A zero backlog and an
  unreachable database look identical on a graph and mean opposite things.

## Structured logs

`LOG_FORMAT=ecs` emits one JSON object per line including `@timestamp`, `log.level`, `log.logger`,
`message`, and the MDC fields `traceId`, `spanId` and `requestId`.

Log records distinguish, deliberately and by separate statements:

- a command attempt from a committed financial effect — nothing logs "committed" before the
  transaction commits;
- a broker acknowledgement from a fenced outbox completion — an acknowledged send whose lease was lost
  is logged as a lost lease, because another worker will send it again;
- a consumer receipt from a committed consumer effect, and a duplicate delivery from a newly applied
  one;
- a business decline from a technical failure.

Never logged: credentials, authorization headers, cookies, CSRF tokens, raw idempotency keys, or whole
request and event bodies. Failure detail is reduced to an exception type and a bounded reason code
rather than free text, so arbitrary input cannot become an indexed field.

## Following one payment end to end

1. **Authorize.** The response carries `X-Request-Id`. With JSON logs on, the same request also has a
   `traceId`.
2. **Find the command.** In Grafana → Explore → Tempo, search by trace id, or filter logs on
   `requestId`. The trace shows the HTTP server span with the payment operation inside it, and
   `decisionrail.decision.duration` shows how much of it was policy evaluation.
3. **Find the event it committed.** The outbox row for that payment carries `origin_trace_id` equal to
   that trace. It was written inside the payment transaction, so it is as durable as the payment.
4. **Watch it publish.** Each publication attempt is a child span named `outbox.publish` in the same
   trace, tagged with the attempt number. A retry is another child, not a longer one.
5. **Watch it land.** The consumer span `consumer.project` continues the same trace, tagged with the
   consumer group and the outcome: `applied`, `duplicate`, `out_of_order` or `quarantined`.
6. **Confirm the effect.** The projection's status for the payment now matches the authoritative
   record, and `decisionrail_consumer_applied_total` incremented once.

A redelivery of the same record appears as another `consumer.project` span in the same trace with
outcome `duplicate`, which is what "the same event arrived twice and produced one effect" looks like.

### A captured example

Taken against the packaged container. One authorization, and the event it committed:

```
$ curl -D - -u demo-merchant:… -H 'Idempotency-Key: walkthrough-…' \
    -d '{"accountId":"…","amountMinor":1500,"currency":"CAD","country":"CA"}' \
    http://localhost:8080/v1/payments/authorizations
X-Request-Id: 12bc9128-3948-4458-945b-4016ec89fcd8

$ psql -c "SELECT aggregate_sequence, event_type, status, origin_trace_id, origin_span_id
           FROM outbox_events WHERE aggregate_id = '1ea7365d-…'"
 aggregate_sequence |      event_type       | status  |         origin_trace_id          |  origin_span_id
--------------------+-----------------------+---------+----------------------------------+------------------
                  1 | payment.authorized.v1 | PENDING | f59f4f7fb7507936fb7fd30a0abd72ad | 9d9896ff516e2457
```

The event was committed with the trace of the request that created it, before any dispatcher had looked
at it. When one does, its publication span joins trace `f59f4f7f…`, and so does the consumer span that
applies the effect — whether that happens a second later or after a restart.

## What the dashboard answers

| Question | Panel |
| --- | --- |
| Which operation is slow or failing? | HTTP latency by route (p95), HTTP rate by outcome |
| Is the delay request handling, database work, or asynchronous processing? | Policy evaluation percentiles next to HTTP latency; database connections in use; publication and consumer durations |
| Is backlog accumulating or draining? | Outbox backlog by status; oldest undelivered event |
| Are retries or duplicates increasing? | Retries and duplicates |
| Is the broker circuit breaker open? | Broker circuit breaker |
| What happened to one synthetic command? | Tempo, by trace id — see the walkthrough above |
