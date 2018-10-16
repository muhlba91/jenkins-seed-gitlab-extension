package org.muehlbachler.jenkins.seed.gitlab.model

enum GitLabEvent {
    PUSH("push"),
    MERGE_REQUEST("merge_request"),
    TAG_PUSH("tag_push"),
    NONE("none")

    final String name

    GitLabEvent(final String name) {
        this.name = name
    }

    static GitLabEvent findByName(final String name, final String name2 = null) {
        GitLabEvent event1 = values().find { it.name == name }
        GitLabEvent event2 = values().find { it.name == name2 }
        return Objects.nonNull(event1) ? event1 : (Objects.nonNull(event2) ? event2 : NONE)
    }
}