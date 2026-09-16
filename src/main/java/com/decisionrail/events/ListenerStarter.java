package com.decisionrail.events;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * Starts the Kafka listener containers after the application is up, and keeps trying until they start.
 *
 * <h2>Why they are not started by the container lifecycle</h2>
 * A listener container builds its consumer when it starts, and building a consumer resolves
 * {@code bootstrap.servers} immediately. When that name does not resolve - a stopped broker container
 * whose DNS entry has gone with it is the ordinary case - the client throws
 * {@code ConfigException: No resolvable bootstrap urls given in bootstrap.servers}, which propagates
 * out of Spring's lifecycle processor and fails the whole application context.
 *
 * <p>So the application refused to start whenever the broker's name was unresolvable. That is the
 * opposite of what this system promises: payments do not need the broker, readiness deliberately
 * excludes it, and the outbox exists so that a broker outage costs delivery rather than the payment
 * API. A restart during an outage was nevertheless a total outage.
 *
 * <p>Note what this is <em>not</em>. A resolvable address whose port is closed constructs a consumer
 * perfectly well and always did; that case never blocked startup. The failure is specifically name
 * resolution at construction time, and the two are not interchangeable.
 *
 * <h2>What this does instead</h2>
 * The factory hands back containers that do not auto-start. This starts them once the context is up,
 * off the startup path, and retries on a fixed interval while the broker is unreachable. When the
 * broker returns the next attempt succeeds and delivery resumes - without another restart, which is
 * the whole point.
 *
 * <p>One daemon thread, one attempt per interval, and it stops scheduling as soon as every container
 * is running. A retry that cannot succeed therefore costs one connection attempt every few seconds
 * rather than a growing pile of work.
 *
 * <p>Retries are for unavailability, not for nonsense. A configuration error that will never resolve
 * itself - an unparseable value, an unknown property - is logged and abandoned rather than retried
 * forever, so genuinely invalid configuration still surfaces instead of being swallowed.
 */
@Component
public class ListenerStarter {
    private static final Logger log = LoggerFactory.getLogger(ListenerStarter.class);
    /** The message Kafka's client uses when no bootstrap address resolves. */
    private static final String UNRESOLVABLE = "No resolvable bootstrap urls";

    private final KafkaListenerEndpointRegistry registry;
    private final boolean enabled;
    private final Duration interval;
    private final ScheduledExecutorService retries =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kafka-listener-starter");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * Containers this class has actually started, by listener id.
     *
     * <p>Kept rather than asking the container, because a container whose start threw can still answer
     * {@code isRunning()} with true: a concurrent container sets itself running while bringing its
     * children up, and a failure part way through leaves that flag set with no consumer behind it.
     * Trusting it reported healthy consumers for a process that had none.
     */
    private final java.util.Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ListenerStarter(KafkaListenerEndpointRegistry registry,
                           @Value("${spring.kafka.listener.auto-startup:true}") boolean enabled,
                           @Value("${app.events.listener-start-interval:5s}") Duration interval) {
        this.registry = registry;
        this.enabled = enabled;
        this.interval = interval;
    }

    /**
     * Whether every listener is running. The asynchronous health indicator reports this, because a
     * process whose consumers never started is degraded in a way the backlog alone does not show.
     */
    public boolean allListenersRunning() {
        if (!enabled) return true;
        java.util.Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        if (containers.isEmpty()) return false;
        return containers.stream()
                .allMatch(container -> started.contains(container.getListenerId()) && container.isRunning());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startListeners() {
        // Deliberately keyed to the same property the container factory used to consult, so a test that
        // asks for no listeners still gets none rather than having them started behind its back.
        if (!enabled) {
            log.info("Kafka listeners are disabled by configuration; none will be started");
            return;
        }
        attemptStart();
    }

    private void attemptStart() {
        if (!running.get()) return;
        boolean allRunning = true;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            String id = container.getListenerId();
            if (started.contains(id)) continue;
            try {
                // A previous attempt can leave the container marked running with no consumer behind
                // it, and start() on something already marked running is a no-op - so the retry would
                // do nothing for ever. Stopping first is what makes the next attempt a real one.
                container.stop();
                container.start();
                if (container.isRunning()) {
                    started.add(id);
                    continue;
                }
                allRunning = false;
            } catch (RuntimeException notYet) {
                allRunning = false;
                stopQuietly(container);
                if (permanentlyMisconfigured(notYet)) {
                    // Not something waiting will fix. Reported and left alone, so a genuine
                    // configuration mistake stays visible instead of being retried into silence.
                    log.error("Listener {} cannot start because its configuration is invalid; not retrying",
                            id, notYet);
                    started.add(id);
                    continue;
                }
                log.warn("Listener {} is waiting for the broker ({}); retrying in {}. "
                                + "The payment API is unaffected.",
                        id, rootMessage(notYet), interval);
            }
        }
        if (allRunning) {
            log.info("All Kafka listeners are running");
            return;
        }
        retries.schedule(this::attemptStart, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Whether this failure is configuration that will never become valid, rather than a broker that is
     * not there yet.
     *
     * <p>The cause chain is walked because the client wraps it: an unresolvable bootstrap address
     * surfaces as a {@code KafkaException} whose cause is the {@code ConfigException}, so matching on
     * the top-level type alone finds nothing and treats every failure the same way.
     */
    private static boolean permanentlyMisconfigured(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConfigException) {
                // The one ConfigException that resolves itself: the broker's name may resolve later.
                return !String.valueOf(cause.getMessage()).contains(UNRESOLVABLE);
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static void stopQuietly(MessageListenerContainer container) {
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // Stopping something that never started is not a problem worth reporting.
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        retries.shutdownNow();
    }
}
