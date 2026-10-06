-- KI-040: a dead letter's identity is its Kafka address PLUS when it was written there.
--
-- V20 keyed a replay on (dlt_topic, dlt_partition, dlt_offset) alone. An address is unique only for
-- one lifetime of a topic: when a dead-letter topic is recreated (or its volume wiped while this
-- database survives) its offsets restart, and a NEW dead letter at a reused offset was refused 409
-- "already replayed" and could never be replayed.
--
-- The record's own timestamp (the broker keeps it with the record) tells the two apart, and it needs
-- no parsing of a payload that may be exactly what was unreadable. Rows written before this
-- migration have no timestamp: NULL means "unknown". The application still treats such a row as a
-- replay of any record at that address written before the replay (a replay follows the write it
-- replays), so what was replayed once is not replayed again; a record written after it is new.
ALTER TABLE dead_letter_replay ADD COLUMN dlt_timestamp TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE dead_letter_replay DROP CONSTRAINT uq_dead_letter_replay_record;
ALTER TABLE dead_letter_replay ADD CONSTRAINT uq_dead_letter_replay_record
    UNIQUE (dlt_topic, dlt_partition, dlt_offset, dlt_timestamp);
