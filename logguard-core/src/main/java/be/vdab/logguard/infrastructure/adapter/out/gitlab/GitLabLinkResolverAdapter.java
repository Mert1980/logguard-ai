package be.vdab.logguard.infrastructure.adapter.out.gitlab;

import be.vdab.logguard.domain.model.ErrorLog;
import be.vdab.logguard.domain.model.GitLabLink;
import be.vdab.logguard.domain.model.StackFrame;
import be.vdab.logguard.domain.port.out.GitLabLinkResolverPort;
import be.vdab.logguard.domain.service.FingerprintComputer;
import be.vdab.logguard.infrastructure.config.LogGuardProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class GitLabLinkResolverAdapter implements GitLabLinkResolverPort {

    private final LogGuardProperties properties;
    private final FingerprintComputer fingerprintComputer;

    public GitLabLinkResolverAdapter(LogGuardProperties properties, FingerprintComputer fingerprintComputer) {
        this.properties         = properties;
        this.fingerprintComputer = fingerprintComputer;
    }

    @Override
    public List<GitLabLink> resolve(ErrorLog errorLog, List<String> packagePrefixes) {
        Optional<LogGuardProperties.GitLabRepositoryProperties> repo = properties.gitlabRepositories()
                .stream()
                .filter(r -> r.appName().equals(errorLog.appName()))
                .findFirst();

        if (repo.isEmpty()) return List.of();

        List<StackFrame> frames = fingerprintComputer.parseOwnCodeFrames(
                errorLog.stackTrace(), packagePrefixes);

        return frames.stream()
                .map(frame -> buildLink(frame, repo.get()))
                .toList();
    }

    private GitLabLink buildLink(StackFrame frame, LogGuardProperties.GitLabRepositoryProperties repo) {
        String path = classToPath(frame.className());
        String url  = repo.repoUrl() + "/-/blob/" + repo.sourceBranch() + "/" + path + "#L" + frame.lineNumber();
        return new GitLabLink(frame.className(), frame.lineNumber(), url);
    }

    /** Converts com.vdab.myapp.MyClass → src/main/java/com/vdab/myapp/MyClass.java */
    private String classToPath(String className) {
        return "src/main/java/" + className.replace('.', '/') + ".java";
    }
}
