package org.muehlbachler.jenkins.seed.gitlab.model

import net.nemerosa.jenkins.seed.cache.ProjectCachedConfig

class GitLabProjectCachedConfigWrapper {
    private final ProjectCachedConfig config;

    GitLabProjectCachedConfigWrapper(final ProjectCachedConfig config) {
        this.config = config
    }

    String getBranchStartJob(final String branch, final String branchJob) {
        return config.getBranchStartJob(branchJob).replaceFirst(branchJob, branch)
    }

    String getBranchSeedJob(final String branch, final String branchJob) {
        return config.getBranchSeedJob(branch)
    }
}
