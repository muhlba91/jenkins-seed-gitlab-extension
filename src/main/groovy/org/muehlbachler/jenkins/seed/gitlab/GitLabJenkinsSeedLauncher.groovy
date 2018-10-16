package org.muehlbachler.jenkins.seed.gitlab

import hudson.model.*
import hudson.security.ACL
import jenkins.model.Jenkins
import net.nemerosa.jenkins.seed.CannotDeleteItemException
import net.nemerosa.jenkins.seed.CannotFindJobException
import net.nemerosa.jenkins.seed.triggering.SeedCause
import net.nemerosa.jenkins.seed.triggering.SeedChannel
import net.nemerosa.jenkins.seed.triggering.SeedLauncher
import org.acegisecurity.context.SecurityContext
import org.acegisecurity.context.SecurityContextHolder
import org.apache.commons.lang.StringUtils

import java.util.logging.Logger

class GitLabJenkinsSeedLauncher implements SeedLauncher {

    private static final Logger LOGGER = Logger.getLogger(GitLabJenkinsSeedLauncher.class.name)

    @Override
    void launch(final SeedChannel channel, final String path, final Map<String, String> parameters) {
        LOGGER.info("Launching job at ${path} with parameters ${parameters}")

        SecurityContext orig = ACL.impersonate(ACL.SYSTEM)
        try {
            final Queue.Task job = findJob(path)
            if (!parameters?.isEmpty()) {
                List<ParameterValue> parameterValues = parameters.collect {
                    new StringParameterValue(it.key, it.value)
                }
                Jenkins.instanceOrNull?.queue?.schedule2(
                        job,
                        0,
                        new ParametersAction(parameterValues),
                        new CauseAction(getCause(channel))
                )
            } else {
                Jenkins.instanceOrNull?.queue?.schedule2(
                        job,
                        0,
                        new CauseAction(getCause(channel))
                )
            }
        } finally {
            SecurityContextHolder.setContext(orig)
        }
    }

    @Override
    void delete(final String path) {
        LOGGER.info("Deleting item at ${path}")

        SecurityContext orig = ACL.impersonate(ACL.SYSTEM)
        try {
            Item root = findItem(path)
            root.allJobs.each {
                LOGGER.info("Deleting item at ${it.name}")
                it.delete()
            }
            LOGGER.info("Deleting item at ${root.name}")
            root.delete()
        } catch (final IOException | InterruptedException e) {
            throw new CannotDeleteItemException(path, e)
        } finally {
            SecurityContextHolder.setContext(orig)
        }
    }

    private Cause getCause(final SeedChannel channel) {
        return new SeedCause(channel)
    }

    private Queue.Task findJob(final String path) {
        Item item = findItem(path)
        LOGGER.finer("Found item ${item?.name} with class ${item?.class?.name} for path ${path}")
        if (item instanceof Queue.Task) {
            return item
        } else {
            throw new CannotFindJobException("", path)
        }
    }

    private Item findItem(final String path) {
        return findItem(Jenkins.instanceOrNull, "", path)
    }

    private Item findItem(final Jenkins jenkins, final String context, final String path) {
        return findItem(jenkins.itemMap, context, path)
    }

    private Item findItem(final Map<String, Item> items, final String context, final String path) {
        LOGGER.finer("Got items ${items?.keySet()} and path ${path}")
        if (StringUtils.contains(path, "/")) {
            String prefix = StringUtils.substringBefore(path, "/")
            String rest = StringUtils.substringAfter(path, "/")

            Item item = items[prefix]
            LOGGER.finer("Found item ${item?.name}, having rest of path ${rest}")
            if (item instanceof ItemGroup) {
                return findItem(item.allItems.collectEntries { [it.name, it] }, "${context}/${prefix}", rest)
            } else {
                throw new CannotFindJobException(context, path)
            }
        } else {
            Item item = items[path]
            LOGGER.finer("Found item ${item?.name} for path ${path}")
            if (Objects.nonNull(item)) {
                return item
            } else {
                throw new CannotFindJobException(context, path)
            }
        }
    }
}
