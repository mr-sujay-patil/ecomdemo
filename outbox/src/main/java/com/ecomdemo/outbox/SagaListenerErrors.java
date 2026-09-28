package com.ecomdemo.outbox;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What a saga listener does with a message it cannot handle: retry it in place a few times, then
 * move it to {@code <topic>-dlt} and carry on.
 *
 * <h2>Blocking retries, unlike notification-service</h2>
 *
 * <p>notification-service uses {@code @RetryableTopic} (Phase 17): a failed message is moved to a
 * retry topic and the partition keeps flowing. That is right for a notification, where the order
 * of two unrelated e-mails does not matter.
 *
 * <p>In the saga it would be wrong. Every event is keyed by the order id, so all of one order's
 * events share a partition and arrive in the order they happened. A retry topic takes a message
 * OUT of that sequence: a {@code StockReserved} parked on a retry topic could be overtaken by the
 * same order's later events. Retrying in place holds the partition for a few seconds instead,
 * which keeps every order's story in order. The cost - other orders on that partition wait - is
 * bounded by the back-off below.
 *
 * <h2>Why a message ends up on the DLT at all</h2>
 *
 * <p>Not because a business rule said no. "Not enough stock" and "card declined" are ordinary
 * saga OUTCOMES, published as events. The DLT is for a message that cannot be processed at all:
 * bytes that are not JSON, or a bug. Phase 17's rule applies: nothing consumes a DLT; it is a
 * queue for a person.
 *
 * <p>The order stays PENDING when that happens, and that is honest: the saga could not finish.
 * Finding such orders is what a saga timeout would do - a follow-up, recorded in the test report.
 */
@Component
public class SagaListenerErrors implements DisposableBean {

    /** The suffix every saga DLT carries; the PUBLISHER of the main topic declares it. */
    public static final String DLT_SUFFIX = "-dlt";

    /** Three attempts in all, one second apart: enough for a database blip, not for an outage. */
    private static final FixedBackOff BACK_OFF = new FixedBackOff(1000L, 2L);

    private final DefaultKafkaProducerFactory<String, byte[]> rawProducerFactory;
    private final KafkaTemplate<String, byte[]> rawTemplate;
    private final KafkaOperations<?, ?> template;

    /**
     * @param applicationProducerFactory Boot's producer factory; its settings (bootstrap servers,
     *     acks, idempotence) are copied, not its serializers
     * @param template the service's own {@code KafkaTemplate}, which dead-letters a message that
     *     WAS deserialised - a record that parsed but failed in the handler
     */
    public SagaListenerErrors(
            ProducerFactory<?, ?> applicationProducerFactory, KafkaOperations<?, ?> template) {
        // A message whose bytes could not be deserialised reaches the recoverer as its RAW bytes.
        // The service's template has a JSON serializer, which would re-encode those bytes as a
        // base64 string - a DLT record nobody could compare with the original. So byte[] gets a
        // producer of its own that writes them verbatim.
        //
        // NOT a bean, for the reason recorded in Phase 18 (`OutboxKafkaSender`): any
        // KafkaTemplate bean makes Boot's own `kafkaTemplate` back off.
        Map<String, Object> properties =
                new HashMap<>(applicationProducerFactory.getConfigurationProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        this.rawProducerFactory = new DefaultKafkaProducerFactory<>(properties);
        this.rawTemplate = new KafkaTemplate<>(rawProducerFactory);
        this.template = template;
    }

    /**
     * The handler a service installs as its listener containers' {@link CommonErrorHandler}.
     *
     * <p>A new handler per call, so a service with two container factories does not share one
     * handler's state between them.
     */
    public DefaultErrorHandler errorHandler() {
        // Checked in order: byte[] first, so raw bytes never reach the JSON template.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, rawTemplate);
        templates.put(Object.class, template);

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        templates,
                        // Partition -1 lets the producer choose. The default would reuse the
                        // failed record's partition number, and every DLT has ONE partition, so a
                        // record from partition 2 would fail to be dead-lettered at all.
                        (record, exception) ->
                                new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        return new DefaultErrorHandler(recoverer, BACK_OFF);
    }

    @Override
    public void destroy() {
        rawProducerFactory.destroy();
    }
}
