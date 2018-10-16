package org.muehlbachler.jenkins.seed.gitlab

import com.google.inject.Guice
import net.nemerosa.jenkins.seed.Constants
import net.nemerosa.jenkins.seed.cache.ProjectCachedConfig
import net.nemerosa.jenkins.seed.cache.ProjectSeedCache
import net.nemerosa.jenkins.seed.triggering.*
import net.nemerosa.jenkins.seed.triggering.connector.RequestNonAuthorizedException
import org.apache.commons.lang.StringUtils
import org.muehlbachler.jenkins.seed.gitlab.model.GitLabProjectCachedConfigWrapper

import java.util.logging.Logger

class GitLabSeedService implements SeedService {

    private static final Logger LOGGER = Logger.getLogger(GitLabSeedService.class.name)

    private final SeedLauncher seedLauncher
    private final ProjectSeedCache seedCache

    GitLabSeedService(final SeedLauncher seedLauncher, final ProjectSeedCache seedCache) {
        this.seedLauncher = seedLauncher
        this.seedCache = seedCache
    }

    GitLabSeedService() {
        this(Guice.createInjector(new GitLabSeedServiceModule()).getInstance(SeedLauncher.class), Guice.createInjector(new SeedServiceModule()).getInstance(ProjectSeedCache.class))
    }

    @Override
    void post(final SeedEvent event) {
        LOGGER.info("Event: project=${event.project}, branch=${event.branch}, type=${event.type}, parameters=${event.parameters}")
        ProjectCachedConfig config = getProjectCachedConfig(event.project)
        checkChannel(event, config)
        post(event, seedLauncher, config)
    }

    @Override
    String getSecretKey(final String project, final String context) {
        ProjectCachedConfig config = getProjectCachedConfig(project)
        return config.secretKey
    }

    void post(final SeedEvent event, final SeedLauncher seedLauncher, final ProjectCachedConfig config) {
        enrichParameters(event)
        switch (event.type) {
            case SeedEventType.CREATION:
                create(event, seedLauncher, config)
                break
            case SeedEventType.DELETION:
                delete(event, seedLauncher, config)
                break
            case SeedEventType.SEED:
                seed(event, seedLauncher, config)
                break
            case SeedEventType.COMMIT:
                commit(event, seedLauncher, config)
                break
            default:
                throw new UnsupportedSeedEventTypeException(event.type)
        }
    }

    private void checkChannel(final SeedEvent event, final ProjectCachedConfig config) {
        if (StringUtils.equals(SeedChannel.SYSTEM.id, event.channel.id)) {
            return
        }
        boolean enabled = config.isChannelEnabled(event.channel)
        if (!enabled) {
            throw new RequestNonAuthorizedException()
        }
    }

    private void enrichParameters(final SeedEvent event) {
        def parameters = event.parameters.collectEntries { [it.key.toUpperCase(), it.value] }
        event.parameters.clear()
        event.parameters.putAll(parameters)
        event.parameters.put(Constants.BRANCH_PARAMETER, event.branch)
    }

    private void commit(final SeedEvent event, final SeedLauncher seedLauncher, final ProjectCachedConfig config) {
        if (config.trigger) {
            String path = getJobBranchPath(event, { branch, branchJob -> new GitLabProjectCachedConfigWrapper(config).getBranchStartJob(branch, branchJob) }, true)
            LOGGER.info("Commit ${event.parameters['COMMIT']} for branch ${event.branch} of project ${event.project} - starting the pipeline at ${path}")
            seedLauncher.launch(event.channel, path, event.parameters as Map<String, String>)
        } else {
            LOGGER.finer("Commit events are not enabled for project ${event.project}")
        }
    }

    private void seed(final SeedEvent event, final SeedLauncher seedLauncher, final ProjectCachedConfig config) {
        if (config.auto) {
            String path = getJobBranchPath(event, { branch, branchJob -> new GitLabProjectCachedConfigWrapper(config).getBranchSeedJob(branch, branchJob) })
            LOGGER.info("Seed files changed for branch ${event.branch} of project ${event.project} - regenerating the pipeline at ${path}")
            seedLauncher.launch(event.channel, path, null)
        } else {
            LOGGER.finer("Seed events are not enabled for project ${event.project}")
        }
    }

    private void delete(final SeedEvent event, final SeedLauncher seedLauncher, final ProjectCachedConfig config) {
        String path = getJobBranchPath(event, { branch, branchJob -> new GitLabProjectCachedConfigWrapper(config).getBranchSeedJob(branch, branchJob) })
        if (config.delete) {
            LOGGER.finer("Deletion of the branch means deletion of the pipeline for project ${event.project}")
            path = StringUtils.substringBeforeLast(path, "/");
            if (StringUtils.isNotBlank(path)) {
                seedLauncher.delete(path)
            }
        } else {
            LOGGER.finer("Deletion of the branch means deletion of the pipeline seed for project ${event.project}");
            seedLauncher.delete(path)
        }
    }

    private void create(final SeedEvent event, final SeedLauncher seedLauncher, final ProjectCachedConfig config) {
        LOGGER.finer("New branch ${event.branch} for project ${event.project} - creating a new pipeline")
        String path = config.getProjectSeedJob()
        seedLauncher.launch(event.channel, path, event.parameters as Map<String, String>)
    }

    private String getJobBranchPath(final SeedEvent event, final Closure fn, final boolean checkMr = false) {
        String branch = event.branch
        String branchSeedJob = event.branch
        if (checkMr && event.parameters['MERGE_REQUEST'] == 'true') {
            branchSeedJob += '-mr'
        }
        return fn.call(branch, branchSeedJob)
    }

    private ProjectCachedConfig getProjectCachedConfig(final String project) {
        ProjectCachedConfig config = seedCache.getProjectPipelineConfig(project)
        if (Objects.isNull(config)) {
            LOGGER.warning("Did not find any cache for project ${project}, using defaults.")
            config = new ProjectCachedConfig(project)
        }
        return config
    }
}
