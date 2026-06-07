package be.vdab.logguard.domain.model;

/** Result of a single delivery attempt to Google Chat (or console in local profile). */
public sealed interface DeliveryResult {

    record Succeeded() implements DeliveryResult {}

    record Failed(String reason) implements DeliveryResult {}
}
