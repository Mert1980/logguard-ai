package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorFingerprint;

import java.time.Duration;

public interface DeduplicationPort {
    boolean isDuplicate(ErrorFingerprint fingerprint);
    void record(ErrorFingerprint fingerprint, Duration window);
}
