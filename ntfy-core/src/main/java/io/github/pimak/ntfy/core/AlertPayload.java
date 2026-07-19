package io.github.pimak.ntfy.core;

/**
 * Immutable {@code {title, body}} pair assembled by {@link AlertEngine} from an {@link AlertEvent}.
 * {@code body} is already truncated to {@link PayloadTruncator#NTFY_MAX_BYTES} UTF-8 bytes by the
 * time it reaches this record.
 */
record AlertPayload(String title, String body) {}
