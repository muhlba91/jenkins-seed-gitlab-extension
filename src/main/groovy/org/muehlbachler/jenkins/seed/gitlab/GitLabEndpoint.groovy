package org.muehlbachler.jenkins.seed.gitlab

import com.google.inject.Guice
import hudson.Extension
import hudson.model.UnprotectedRootAction
import hudson.security.csrf.CrumbExclusion
import net.nemerosa.jenkins.seed.triggering.SeedChannel
import net.nemerosa.jenkins.seed.triggering.SeedEvent
import net.nemerosa.jenkins.seed.triggering.SeedEventType
import net.nemerosa.jenkins.seed.triggering.SeedService
import net.nemerosa.jenkins.seed.triggering.connector.RequestNonAuthorizedException
import net.sf.json.JSON
import net.sf.json.JSONObject
import net.sf.json.JSONSerializer
import org.apache.commons.io.Charsets
import org.apache.commons.io.IOUtils
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.StaplerResponse
import org.kohsuke.stapler.interceptor.RequirePOST
import org.kohsuke.stapler.interceptor.RespondSuccess
import org.muehlbachler.jenkins.seed.gitlab.model.*

import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.nio.charset.Charset
import java.util.logging.Level
import java.util.logging.Logger

@Extension
class GitLabEndpoint extends CrumbExclusion implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(GitLabEndpoint.class.name);
    private static final String GITLAB_TOKEN_HEADER = "X-Gitlab-Token"
    private static final String GITLAB_ENDPOINT_URL = "seed-gitlab-api"
    private static final String GITLAB_CHANNEL = "gitlab"
    private static final SeedChannel SEED_CHANNEL = SeedChannel.of(GITLAB_CHANNEL, "GitLab Seed Extension")

    private final SeedService seedService;

    GitLabEndpoint(final SeedService seedService) {
        this.seedService = seedService;
    }

    GitLabEndpoint() {
        this(Guice.createInjector(new GitLabSeedServiceModule()).getInstance(SeedService.class))
    }

    @RequirePOST
    @RespondSuccess
    void doInvoke(final StaplerRequest req, final StaplerResponse response) throws IOException {
        LOGGER.info("Got message: ${req.method}, ${req.contentLength}")

        try {
            SeedEvent event = extractEvent(req)
            if (Objects.isNull(event)) {
                LOGGER.finer("Event not managed")
                sendError(response, "Event not managed")
            } else {
                LOGGER.finer("Event to process: project=${event.project}, branch=${event.branch}, type=${event.type}, parameters=${event.parameters}")
                if (event.type == SeedEventType.TEST) {
                    sendError(response, "Test OK")
                } else {
                    post(event)
                    sendOk(response, event)
                }
            }
        } catch (final IOException ex) {
            throw ex
        } catch (final RequestNonAuthorizedException ex) {
            sendError(response, ex.message)
        } catch (final Exception ex) {
            LOGGER.log(Level.SEVERE, ex.message, ex);
            sendError(response, ex.message)
        }
    }

    @Override
    String getIconFileName() {
        return null
    }

    @Override
    String getDisplayName() {
        return "GitLab Seed WebHook"
    }

    @Override
    String getUrlName() {
        return GITLAB_ENDPOINT_URL
    }

    @Override
    boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        final String pathInfo = request.pathInfo
        if (pathInfo != null && pathInfo.startsWith("/${GITLAB_ENDPOINT_URL}/")) {
            chain.doFilter(request, response)
            return true
        }
        return false
    }

    private void post(final SeedEvent event) {
        seedService.post(event)
    }

    private void sendOk(final StaplerResponse response, final SeedEvent event) throws IOException {
        response.setContentType("application/json")
        response.setStatus(HttpServletResponse.SC_OK)
        JSON json = JSONSerializer.toJSON([
                status: "OK",
                event : [
                        project   : event.project,
                        branch    : event.branch,
                        type      : event.type,
                        parameters: event.parameters
                ]
        ])
        json.write(response.writer)
    }

    private void sendError(final StaplerResponse response, final String message) throws IOException {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, message)
    }

    private SeedEvent extractEvent(final StaplerRequest req) throws IOException {
        Charset charset = req.characterEncoding ? Charset.forName(req.characterEncoding) : Charsets.UTF_8
        JSONObject data = JSONObject.fromObject(IOUtils.toString(req.inputStream, charset))

        GitLabProject project = getProject(data)
        GitLabEvent event = GitLabEvent.findByName(data.optString("event_name", null), data.optString("event_type", null))
        GitLabEventKind eventKind = getEventKind(data, event)
        GitLabBranch branch = getBranch(event, data)

        String token = req.getHeader(GITLAB_TOKEN_HEADER)
        checkToken(token, seedService.getSecretKey(getProjectName(project), "gitlab"))
        def commit = getCommit(event, data)

        if (eventKind == GitLabEventKind.DELETE && event != GitLabEvent.MERGE_REQUEST) {
            return deleteEvent(project, branch, event, commit)
        }

        if (eventKind == GitLabEventKind.CREATE) {
            return createEvent(project, branch, event, commit)
        }

        if (eventKind == GitLabEventKind.UPDATE) {
            return commitEvent(project, branch, event, commit)
        }

        LOGGER.warning("Couldn't find correct event kind for: ${data}")
        return null
    }

    private checkToken(final String token, final String storedToken) {
        if (storedToken != token) {
            throw new RequestNonAuthorizedException()
        }
    }

    private GitLabProject getProject(final JSONObject data) {
        def projectData = data.getJSONObject("project")
        GitLabProject project = new GitLabProject()
        project.id = projectData.getString("id")
        project.name = projectData.getString("name")
        project.namespace = projectData.getString("namespace")
        project.path = projectData.getString("path_with_namespace")
        project.gitSshUrl = projectData.getString("git_ssh_url")
        return project
    }

    private GitLabEventKind getEventKind(final JSONObject data, final GitLabEvent event) {
        switch (event) {
            case GitLabEvent.PUSH:
            case GitLabEvent.TAG_PUSH:
                return getPushEventKind(data.getString("before"), data.getString("after"))
            case GitLabEvent.MERGE_REQUEST:
                return getMergeRequestEventKind(data)
            default:
                return GitLabEventKind.NONE
        }
    }

    private GitLabBranch getBranch(final GitLabEvent event, final JSONObject data) {
        String branch
        switch (event) {
            case GitLabEvent.PUSH:
            case GitLabEvent.TAG_PUSH:
                branch = data.getString("ref")
                break
            case GitLabEvent.MERGE_REQUEST:
                branch = data.getJSONObject("object_attributes").getString("source_branch")
                break
            default:
                branch = null
        }

        GitLabBranch gitLabBranch = new GitLabBranch()
        gitLabBranch.ref = branch.replace("refs/heads/", "").replace("refs/tags/", "").trim()
        return gitLabBranch
    }

    private GitLabEventKind getPushEventKind(final String commitBefore, final String commitAfter) {
        if (commitBefore.replaceAll("0", "").trim().empty) {
            return GitLabEventKind.CREATE
        }

        if (commitAfter.replaceAll("0", "").trim().empty) {
            return GitLabEventKind.DELETE
        }

        return GitLabEventKind.UPDATE
    }

    private GitLabEventKind getMergeRequestEventKind(final JSONObject data) {
        String action = data.getJSONObject("object_attributes").getString("action")
        switch (action) {
            case "open":
                return GitLabEventKind.CREATE
            case "merge":
            case "close":
                return GitLabEventKind.DELETE
            default: // includes 'update'
                return GitLabEventKind.UPDATE
        }
    }

    private GitLabCommit getCommit(final GitLabEvent event, final JSONObject data) {
        if (event == GitLabEvent.PUSH || event == GitLabEvent.TAG_PUSH) {
            String authorName = data.getString("user_name")
            String authorUser = data.getString("user_username")
            GitLabAuthor author = new GitLabAuthor()
            author.userName = authorUser
            author.name = authorName

            String sha = data.getString("checkout_sha")
            GitLabCommit commit = new GitLabCommit()
            commit.sha = sha
            commit.author = author
            return commit
        }

        if (event == GitLabEvent.MERGE_REQUEST) {
            JSONObject user = data.getJSONObject("user")
            String authorName = user.getString("username")
            String authorUser = user.getString("name")
            GitLabAuthor author = new GitLabAuthor()
            author.userName = authorUser
            author.name = authorName

            JSONObject mergeData = data.getJSONObject("object_attributes")
            String sha = mergeData.getJSONObject("last_commit").getString("id")
            String id = mergeData.getString("id")
            String sourceBranch = mergeData.getString("source_branch")
            String targetBranch = mergeData.getString("target_branch")
            GitLabMergeRequest mergeRequest = new GitLabMergeRequest()
            mergeRequest.sha = sha
            mergeRequest.author = author
            mergeRequest.id = id
            mergeRequest.sourceBranch = sourceBranch
            mergeRequest.targetBranch = targetBranch
            return mergeRequest
        }

        return null
    }

    private SeedEvent deleteEvent(final GitLabProject project, final GitLabBranch branch, final GitLabEvent event, final GitLabCommit commit) {
        return branchEvent(project, branch.ref, SeedEventType.DELETION, commit, event == GitLabEvent.MERGE_REQUEST, event == GitLabEvent.TAG_PUSH)
    }

    private SeedEvent createEvent(final GitLabProject project, final GitLabBranch branch, final GitLabEvent event, final GitLabCommit commit) {
        if (event == GitLabEvent.PUSH) {
            return branchEvent(project, branch.ref, SeedEventType.CREATION, commit, false)
        }

        if (event == GitLabEvent.TAG_PUSH) {
            return branchEvent(project, branch.ref, SeedEventType.CREATION, commit, false, true)
        }

        if (event == GitLabEvent.MERGE_REQUEST) {
            return branchEvent(project, branch.ref, SeedEventType.CREATION, commit, true)
        }

        return null
    }

    private SeedEvent commitEvent(final GitLabProject project, final GitLabBranch branch, final GitLabEvent event, final GitLabCommit commit) {
        return branchEvent(project, branch.ref, SeedEventType.COMMIT, commit, event == GitLabEvent.MERGE_REQUEST, event == GitLabEvent.TAG_PUSH)
    }

    private SeedEvent branchEvent(final GitLabProject project, final String branch, final SeedEventType type, final GitLabCommit commit = null, final boolean isMergeRequest = false, final boolean isTagEvent = false) {
        SeedEvent event = new SeedEvent(getProjectName(project), branch, type, SEED_CHANNEL)
                .withParam("namespace", project.namespace)
                .withParam("name", project.name)
                .withParam("merge_request", isMergeRequest.toString())
                .withParam("tag_event", isTagEvent.toString())
                .withParam("commit", commit?.sha ?: "")
                .withParam("author_user", commit?.author?.userName ?: "")
                .withParam("author_name", commit?.author?.name ?: "")
        if (commit instanceof GitLabMergeRequest) {
            event = event.withParam("mr_id", commit.id)
                    .withParam("source_branch", commit.sourceBranch)
                    .withParam("target_branch", commit.targetBranch)
        } else {
            event = event.withParam("mr_id", "")
                    .withParam("source_branch", "")
                    .withParam("target_branch", "")
        }
        return event
    }

    private String getProjectName(final GitLabProject project) {
        return project.path
    }
}
