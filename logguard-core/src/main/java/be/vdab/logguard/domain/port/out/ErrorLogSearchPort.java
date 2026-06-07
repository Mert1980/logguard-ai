package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;

import java.time.Instant;
import java.util.List;

public interface ErrorLogSearchPort {
    boolean isReachable();
    List<ErrorLog> findSince(Instant since);
}
