package com.jeltechnologies.keycunda.user;

import java.util.List;

/**
 * The fixed set of groups users can be put in. The same names are granted permissions in Camunda's
 * Orchestration cluster by the Helm values (camunda.security.initialization.authorizations in
 * template-values-camunda-8.10.yaml), so adding a group here without a matching authorization there
 * gives its members nothing. Group names are the Camunda group IDs (the `groups` token claim).
 */
public final class Groups {

    /** Everything: Operate, Tasklist, all processes, Identity/Admin. */
    public static final String ADMINS = "admins";
    /** Operate and Tasklist, deploy and run any process. */
    public static final String DEVELOPERS = "developers";
    /** Tasklist only; may start the ticket process and work its user tasks. */
    public static final String TASK_USERS = "task-users";

    public static final List<String> ALL = List.of(ADMINS, DEVELOPERS, TASK_USERS);

    private Groups() {
    }
}
