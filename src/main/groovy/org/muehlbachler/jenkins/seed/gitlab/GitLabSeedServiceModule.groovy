package org.muehlbachler.jenkins.seed.gitlab

import com.google.inject.AbstractModule
import net.nemerosa.jenkins.seed.triggering.SeedLauncher
import net.nemerosa.jenkins.seed.triggering.SeedService

class GitLabSeedServiceModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(SeedService.class).to(GitLabSeedService.class)
        bind(SeedLauncher.class).to(GitLabJenkinsSeedLauncher.class)
    }
}
