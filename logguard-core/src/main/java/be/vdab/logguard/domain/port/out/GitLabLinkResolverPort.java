package be.vdab.logguard.domain.port.out;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.GitLabLink;

import java.util.List;

public interface GitLabLinkResolverPort {
    List<GitLabLink> resolve(ErrorLog errorLog, List<String> packagePrefixes);
}
