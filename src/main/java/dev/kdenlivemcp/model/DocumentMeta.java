package dev.kdenlivemcp.model;

/**
 * Document-level identifiers and anything we keep verbatim from the file.
 *
 * @param sequenceUuid the timeline sequence uuid, with braces, e.g. {@code {dd00b816-...}}
 * @param documentId Kdenlive's {@code docproperties.documentid} (epoch millis at creation)
 */
public record DocumentMeta(String sequenceUuid, String documentId, Extras extras) {

    public static DocumentMeta fresh() {
        return new DocumentMeta("{" + java.util.UUID.randomUUID() + "}",
                Long.toString(System.currentTimeMillis()), Extras.NONE);
    }
}
