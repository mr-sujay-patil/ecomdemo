package com.ecomdemo.deadletter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Listing and replaying dead-lettered saga events, against the real Kafka container and the real
 * {@code dead_letter_replay} table.
 *
 * <p>The record is written to the dead-letter topic by hand, with the headers Spring Kafka's
 * recoverer would have added, rather than by making a listener fail three times: what this test is
 * about starts where the recoverer stops.
 */
class DeadLetterIT extends IntegrationTest {

    private static final String DLT = "inventory.stock-reserved-dlt";
    private static final String ORIGINAL = "inventory.stock-reserved";

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private Map<String, Object> kafka() {
        Map<String, Object> properties = new HashMap<>(consumerFactory.getConfigurationProperties());
        properties.remove(ConsumerConfig.GROUP_ID_CONFIG);
        return properties;
    }

    /** Writes one record to the DLT as the recoverer would, and returns its offset. */
    private RecordMetadata deadLetter(String key, String json) throws Exception {
        Map<String, Object> properties = kafka();
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        properties.keySet().removeIf(name -> name.startsWith("key.deserializer") || name.startsWith("value.deserializer"));
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(properties)) {
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(DLT, key, json.getBytes(StandardCharsets.UTF_8));
            record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, ORIGINAL.getBytes(StandardCharsets.UTF_8));
            record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN,
                    "java.lang.IllegalStateException".getBytes(StandardCharsets.UTF_8));
            record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                    "payment-service could not handle it".getBytes(StandardCharsets.UTF_8));
            return producer.send(record).get(10, TimeUnit.SECONDS);
        }
    }

    /** The record with this key on the original topic, if a replay put one there. */
    private Optional<ConsumerRecord<String, byte[]>> onOriginalTopic(String key) {
        Map<String, Object> properties = kafka();
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = consumer.partitionsFor(ORIGINAL, Duration.ofSeconds(10)).stream()
                    .map(info -> new TopicPartition(ORIGINAL, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Instant giveUp = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(giveUp)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
        }
    }

    private List<DeadLetterView> listing() {
        ResponseEntity<List<DeadLetterView>> response = asAdmin().exchange(
                "/api/admin/dead-letters", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String replayUrl(RecordMetadata at) {
        return "/api/admin/dead-letters/%s/%d/%d/replay".formatted(DLT, at.partition(), at.offset());
    }

    @Test
    @DisplayName("lists a dead-lettered record with why it is there, replays it byte for byte, and records who did")
    void listAndReplay() throws Exception {
        String key = String.valueOf(900_000 + (int) (Math.random() * 99_999));
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"orderId\":" + key + "}";
        RecordMetadata at = deadLetter(key, json);

        DeadLetterView listed = listing().stream()
                .filter(view -> view.topic().equals(DLT) && view.offset() == at.offset()
                        && view.partition() == at.partition())
                .findFirst()
                .orElseThrow();
        assertThat(listed.key()).isEqualTo(key);
        assertThat(listed.originalTopic()).isEqualTo(ORIGINAL);
        assertThat(listed.exceptionMessage()).isEqualTo("payment-service could not handle it");
        assertThat(listed.payload()).isEqualTo(json);
        assertThat(listed.replayed()).isFalse();

        ResponseEntity<ReplayView> replayed = asAdmin().postForEntity(replayUrl(at), null, ReplayView.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().originalTopic()).isEqualTo(ORIGINAL);
        assertThat(replayed.getBody().replayedBy()).isEqualTo(ADMIN_USERNAME);

        ConsumerRecord<String, byte[]> back = onOriginalTopic(key).orElseThrow();
        assertThat(new String(back.value(), StandardCharsets.UTF_8)).as("the same bytes").isEqualTo(json);
        assertThat(new String(back.headers().lastHeader(DeadLetterService.REPLAYED_FROM_HEADER).value(),
                        StandardCharsets.UTF_8))
                .isEqualTo("%s/%d/%d".formatted(DLT, at.partition(), at.offset()));

        // Once only: the audit row's unique key refuses a second replay of the same record.
        assertThat(asAdmin().postForEntity(replayUrl(at), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(listing().stream().filter(view -> view.offset() == at.offset()
                        && view.partition() == at.partition() && view.topic().equals(DLT)))
                .allMatch(DeadLetterView::replayed);

        ResponseEntity<List<ReplayView>> log = asAdmin().exchange(
                "/api/admin/dead-letters/replays", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(log.getBody()).anyMatch(entry -> entry.dltTopic().equals(DLT)
                && entry.dltOffset() == at.offset() && entry.key().equals(key));
    }

    /** An audit row for an address, as an earlier replay (or an earlier life of the topic) left it. */
    private void earlierReplayAt(RecordMetadata at, Instant writtenAt, Instant replayedAt) {
        jdbc.update("INSERT INTO dead_letter_replay (dlt_topic, dlt_partition, dlt_offset, dlt_timestamp, "
                        + "original_topic, record_key, replayed_by, replayed_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'earlier', 'admin', ?)",
                DLT, at.partition(), at.offset(), writtenAt == null ? null : java.sql.Timestamp.from(writtenAt),
                ORIGINAL, java.sql.Timestamp.from(replayedAt));
    }

    @Test
    @DisplayName("KI-040: a new record at an address an earlier life of the topic used can be replayed")
    void aReusedAddressIsANewRecord() throws Exception {
        String key = String.valueOf(900_000 + (int) (Math.random() * 99_999));
        RecordMetadata at = deadLetter(key, "{\"orderId\":" + key + "}");
        // The same topic, partition and offset replayed back when the topic was an earlier one.
        Instant earlier = Instant.ofEpochMilli(at.timestamp()).minus(Duration.ofDays(3));
        earlierReplayAt(at, earlier, earlier.plusSeconds(60));

        assertThat(listing().stream().filter(view -> view.offset() == at.offset()
                        && view.partition() == at.partition() && view.topic().equals(DLT)))
                .as("not shown as replayed: it is another record")
                .noneMatch(DeadLetterView::replayed);
        assertThat(asAdmin().postForEntity(replayUrl(at), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(asAdmin().postForEntity(replayUrl(at), null, String.class).getStatusCode())
                .as("and that record, once, is still once")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("KI-040: an audit row from before V21 (no timestamp) blocks the record it may have replayed")
    void aRowWithoutATimestampBlocksARecordItCouldHaveReplayed() throws Exception {
        String key = String.valueOf(900_000 + (int) (Math.random() * 99_999));
        RecordMetadata at = deadLetter(key, "{\"orderId\":" + key + "}");
        // Replayed after the record was written: it may well be this record, so it stays replayed once.
        earlierReplayAt(at, null, Instant.now().plusSeconds(1));

        assertThat(asAdmin().postForEntity(replayUrl(at), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("KI-040: an audit row from before V21 does not block a record written after that replay")
    void aRowWithoutATimestampDoesNotBlockALaterRecord() throws Exception {
        String key = String.valueOf(900_000 + (int) (Math.random() * 99_999));
        RecordMetadata at = deadLetter(key, "{\"orderId\":" + key + "}");
        // A replay can only follow the write it replays, so this one was of an earlier record.
        earlierReplayAt(at, null, Instant.ofEpochMilli(at.timestamp()).minus(Duration.ofDays(3)));

        assertThat(asAdmin().postForEntity(replayUrl(at), null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("refuses what is not a saga dead-letter record: another topic, or an offset that is not there")
    void refusesWhatIsNotThere() {
        assertThat(asAdmin().postForEntity(
                        "/api/admin/dead-letters/orders.placed-dlt/0/0/replay", null, String.class).getStatusCode())
                .as("notification's DLT is not the saga's")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(asAdmin().postForEntity(
                        "/api/admin/dead-letters/" + DLT + "/0/999999999/replay", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("is an administrator's tool: a shopper gets 403")
    void shoppersMayNot() {
        assertThat(asCustomer("it-dead-letter-shopper")
                        .getForEntity("/api/admin/dead-letters", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(asCustomer("it-dead-letter-shopper")
                        .postForEntity("/api/admin/dead-letters/" + DLT + "/0/0/replay", null, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
