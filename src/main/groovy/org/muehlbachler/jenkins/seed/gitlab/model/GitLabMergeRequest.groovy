package org.muehlbachler.jenkins.seed.gitlab.model

class GitLabMergeRequest extends GitLabCommit {
    String id
    String sourceBranch
    String targetBranch
}
