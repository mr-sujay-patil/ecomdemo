package com.ecomdemo.deadletter.internal;

import com.ecomdemo.messaging.KafkaTopics;
import com.ecomdemo.shared.ConflictException;
import com.ecomdemo.shared.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the saga's dead-letter topics and sends a record back where it came from (Phase 32).
 *
 * <h2>Reading a topic without joining a group</h2>
 *
 * <p>The listing uses a short-lived consumer that ASSIGNS itself every partition rather than
 * subscribing with a group id. A group would commit offsets, and the next listing would then start
 * where this one stopped and show nothing: a dead-letter topic is a record of what went wrong, to be
 * read in full every time, not a queue to be drained. {@code assign} + {@code seekToBeginning} reads
 * it like a log file.
 *
 * <h2>Replaying the bytes, not a re-serialisation</h2>
 *
 * <p>The record goes back exactly as it arrived - same key, same value bytes - through a producer
 * with byte-array serialisers. Parsing it and serialising it again could "repair" or change it, and
 * a record that was dead-lettered because its bytes were bad would then be replayed as something it
 * never was. The only addition is a header naming where it was replayed from.
 *
 * <h2>Why replay is safe</h2>
 *
 * <p>Every saga consumer claims each event id in {@code processed_event}, so a record that WAS
 * processed before it was dead-lettered changes nothing the second time. And an order the saga
 * deadline has already decided is fenced on both sides: inventory refuses to reserve for a closed
 * order, payment refuses to charge a voided one.
 */
@Service
public class DeadLetterService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /** The saga's five topics; each has a {@code -dlt} twin declared by its publisher. */
    static final List<String> SAGA_TOPICS = List.of(
            KafkaTopics.ORDERS_CREATED,
            "inventory.stock-reserved",
            KafkaTopics.STOCK_REJECTED,
            KafkaTopics.PAYMENTS_COMPLETED,
            KafkaTopics.PAYMENTS_FAILED);

    static final Set<String> SAGA_DEAD_LETTER_TOPICS = Set.copyOf(
            SAGA_TOPICS.stream().map(topic -> topic + KafkaTopics.DLT_SUFFIX).toList());

    /** Added to a replayed record: {@code <dlt topic>/<partition>/<offset>}. */
    static final String REPLAYED_FROM_HEADER = "ecomdemo-replayed-from";

    /** The most records one listing returns, oldest first. */
    static final int LISTING_LIMIT = 500;

    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(10);
    private static final int PAYLOAD_PREVIEW = 2000;

    private final Map<String, Object> consumerProperties;
    private final Producer<String, byte[]> producer;
    private final DeadLetterReplayRepository replays;

    DeadLetterService(
            ConsumerFactory<?, ?> consumerFactory,
            ProducerFactory<?, ?> producerFactory,
            DeadLetterReplayRepository replays) {
        this.replays = replays;

        // Boot's settings (bootstrap servers, security) with this module's own deserialisers, and
        // no group - see the class comment.
        Map<String, Object> consumer = new HashMap<>(consumerFactory.getConfigurationProperties());
        consumer.remove(ConsumerConfig.GROUP_ID_CONFIG);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumer.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        this.consumerProperties = consumer;

        // A private producer, not a bean, for the reason recorded in Phase 18 (OutboxKafkaSender):
        // a second KafkaTemplate bean would make Boot's own back off.
        Map<String, Object> producer = new HashMap<>(producerFactory.getConfigurationProperties());
        producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producer.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        this.producer = new KafkaProducer<>(producer);
    }

    /**
     * Everything waiting in the saga's dead-letter topics, oldest first per partition.
     *
     * <p>No transaction: this polls Kafka for up to ten seconds, and a database connection held for
     * that long is one the checkout cannot have (Phase 30's pool lesson).
     */
    public List<DeadLetterView> list() {
        List<DeadLetterView> found = new ArrayList<>();
        try (Consumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProperties)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : SAGA_DEAD_LETTER_TOPICS.stream().sorted().toList()) {
                List<PartitionInfo> infos = consumer.partitionsFor(topic, KAFKA_TIMEOUT);
                if (infos != null) {
                    infos.forEach(info -> partitions.add(new TopicPartition(topic, info.partition())));
                }
            }
            if (partitions.isEmpty()) {
                return found;
            }

            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, KAFKA_TIMEOUT);
            List<TopicPartition> unread = new ArrayList<>(partitions);
            unread.removeIf(partition -> consumer.position(partition, KAFKA_TIMEOUT) >= end.get(partition));

            Instant giveUp = Instant.now().plus(KAFKA_TIMEOUT);
            while (!unread.isEmpty() && found.size() < LISTING_LIMIT && Instant.now().isBefore(giveUp)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    if (found.size() < LISTING_LIMIT) {
                        found.add(view(record));
                    }
                }
                unread.removeIf(partition -> consumer.position(partition, KAFKA_TIMEOUT) >= end.get(partition));
            }
        }
        return found;
    }

    /**
     * Sends one dead-lettered record back to the topic it came from, and records who did it.
     *
     * <p>The audit row is written and FLUSHED before the send, so a second replay of the same record
     * - even one racing this call from another instance - fails on the unique key instead of sending
     * twice. If the send then fails, the transaction rolls back and the record may be replayed again.
     * That ordering is why this one method does hold a connection across a Kafka call: it is a rare,
     * deliberate operator action, and "recorded if and only if sent" is worth more here than the
     * connection is.
     *
     * @throws NotFoundException if the topic is not a saga dead-letter topic, or holds no such record
     * @throws ConflictException if that record has already been replayed
     */
    @Transactional
    public ReplayView replay(String dltTopic, int partition, long offset, String replayedBy) {
        if (!SAGA_DEAD_LETTER_TOPICS.contains(dltTopic)) {
            throw new NotFoundException("Not a saga dead-letter topic: " + dltTopic);
        }
        // Fetched first: the record's write time is part of its identity (KI-040), and only the
        // record knows it.
        ConsumerRecord<String, byte[]> record = fetch(dltTopic, partition, offset)
                .orElseThrow(() -> new NotFoundException(
                        "No record at %s/%d/%d".formatted(dltTopic, partition, offset)));
        if (replays.isReplayed(dltTopic, partition, offset, Instant.ofEpochMilli(record.timestamp()))) {
            throw new ConflictException(
                    "%s/%d/%d has already been replayed; see the replay log".formatted(dltTopic, partition, offset));
        }
        String originalTopic = originalTopic(record);

        DeadLetterReplay audit;
        try {
            audit = replays.saveAndFlush(new DeadLetterReplay(
                    dltTopic, partition, offset, Instant.ofEpochMilli(record.timestamp()), originalTopic, record.key(),
                    replayedBy, Instant.now()));
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException(
                    "%s/%d/%d has already been replayed; see the replay log".formatted(dltTopic, partition, offset));
        }

        ProducerRecord<String, byte[]> replayed =
                new ProducerRecord<>(originalTopic, null, record.key(), record.value());
        replayed.headers().add(REPLAYED_FROM_HEADER,
                "%s/%d/%d".formatted(dltTopic, partition, offset).getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(replayed).get(KAFKA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not replay %s/%d/%d to %s"
                    .formatted(dltTopic, partition, offset, originalTopic), e);
        }
        log.warn("Replayed dead-lettered record {}/{}/{} (key {}) to {}, by {}",
                dltTopic, partition, offset, record.key(), originalTopic, replayedBy);
        return ReplayView.of(audit);
    }

    /** The replay audit trail, newest first. */
    @Transactional(readOnly = true)
    public List<ReplayView> replays() {
        return replays.findTop200ByOrderByReplayedAtDesc().stream().map(ReplayView::of).toList();
    }

    private Optional<ConsumerRecord<String, byte[]>> fetch(String topic, int partition, long offset) {
        try (Consumer<String, byte[]> consumer = new KafkaConsumer<>(consumerProperties)) {
            TopicPartition target = new TopicPartition(topic, partition);
            List<PartitionInfo> infos = consumer.partitionsFor(topic, KAFKA_TIMEOUT);
            if (infos == null || infos.stream().noneMatch(info -> info.partition() == partition)) {
                return Optional.empty();
            }
            consumer.assign(List.of(target));
            Long end = consumer.endOffsets(List.of(target), KAFKA_TIMEOUT).get(target);
            Long start = consumer.beginningOffsets(List.of(target), KAFKA_TIMEOUT).get(target);
            if (offset < start || offset >= end) {
                return Optional.empty();
            }
            consumer.seek(target, offset);
            Instant giveUp = Instant.now().plus(KAFKA_TIMEOUT);
            while (Instant.now().isBefore(giveUp)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.offset() == offset) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
        }
    }

    private DeadLetterView view(ConsumerRecord<String, byte[]> record) {
        String payload = record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8);
        if (payload != null && payload.length() > PAYLOAD_PREVIEW) {
            payload = payload.substring(0, PAYLOAD_PREVIEW) + "…";
        }
        return new DeadLetterView(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                Instant.ofEpochMilli(record.timestamp()),
                originalTopic(record),
                header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                payload,
                replays.isReplayed(
                        record.topic(), record.partition(), record.offset(), Instant.ofEpochMilli(record.timestamp())));
    }

    /** The topic the record failed on: the recoverer's header, or the DLT's name without its suffix. */
    private static String originalTopic(ConsumerRecord<String, byte[]> record) {
        String header = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (header != null) {
            return header;
        }
        String topic = record.topic();
        return topic.substring(0, topic.length() - KafkaTopics.DLT_SUFFIX.length());
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null
                ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void destroy() {
        producer.close(Duration.ofSeconds(5));
    }
}
