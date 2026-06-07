package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.LLMAnalysis;

public interface LLMAnalysisPort {
    LLMAnalysis analyse(ErrorLog errorLog);
}
