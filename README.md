# Jenkins Seed - GitLab Extension

This image provides a (highly customized) extension for [GitLab](https://gitlab.com) for the [Jenkins](https://jenkins.io) [Seed plugin](https://github.com/jenkinsci/seed-plugin).

## Project Status

[![Build Status](https://travis-ci.org/muhlba91/jenkins-seed-gitlab-extension.svg?branch=master)](https://travis-ci.org/muhlba91/rancher-compose-docker)

## Usage

Build the `.hpi` file using
```
./gradlew jpi
```
and upload the generated file, located in `build/libs` to your Jenkins instance.
For general setup, follow the Seed and [GitLab plugin](https://github.com/jenkinsci/gitlab-plugin), especially for saving the credentials to your GitLab instance.

In GitLab create a WebHook to `http://<jenkins>/seed-gitlab-api/invoke` and in Jenkins create a Seed job according to the Seed plugin.
In Jenkins make sure to pass through the required variables, as follows:

| Variable | Description |
|----------|-------------|
| NAMESPACE | project namespace |
| NAME | project name |
| MERGE_REQUEST | indicates if it is a merge request |
| TAG_EVENT | indicates if it is a tag event |
| AUTHOR_USER | author username |
| AUTHOR_NAME | author name |
| MR_ID | the merge request id |
| SOURCE_BRANCH | source branch of a merge request |
| TARGET_BRANCH | target branch of a merge request |

Moreover, the **current commit** will be in a variable named `COMMIT`.

## Contributions

Submit an issue describing the problem(s)/question(s) and proposed fixes/work-arounds.

To contribute, just fork the repository, develop and test your code changes and submit a pull request.
