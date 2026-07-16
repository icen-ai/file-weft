package ai.icen.fw.release.smoke.maven;

import ai.icen.fw.starter.boot3.FileWeftAutoConfiguration;
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3AutoConfiguration;

/** Compiles the published Boot 3 starters through Maven's POM-only model. */
public final class MavenBoot3Consumer {
    public Class<FileWeftAutoConfiguration> coreStarter() {
        return FileWeftAutoConfiguration.class;
    }

    public Class<FileWeftWebBoot3AutoConfiguration> webStarter() {
        return FileWeftWebBoot3AutoConfiguration.class;
    }
}
