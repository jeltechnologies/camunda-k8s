package com.jeltechnologies.keycunda.user;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Group memberships of users (table user_groups); the valid group names are in {@link Groups}. */
@Repository
public class UserGroupRepository {

    private final JdbcClient jdbcClient;

    public UserGroupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Group names of the user with this id, in the fixed {@link Groups#ALL} order. */
    public List<String> findGroupNames(UUID userId) {
        List<String> names = jdbcClient.sql("SELECT group_name FROM user_groups WHERE user_id = :id")
                .param("id", userId).query(String.class).list();
        return Groups.ALL.stream().filter(names::contains).toList();
    }

    /** Group names of the user with this email (login identifier), for the token's groups claim. */
    public List<String> findGroupNamesByEmail(String email) {
        List<String> names = jdbcClient.sql("""
                SELECT g.group_name FROM user_groups g JOIN users u ON u.id = g.user_id WHERE u.email = :email
                """).param("email", email).query(String.class).list();
        return Groups.ALL.stream().filter(names::contains).toList();
    }

    /** Replaces the user's groups; names that are not in {@link Groups#ALL} are ignored. */
    @Transactional
    public void setGroups(UUID userId, Collection<String> groupNames) {
        jdbcClient.sql("DELETE FROM user_groups WHERE user_id = :id").param("id", userId).update();
        if (groupNames == null) {
            return;
        }
        for (String name : groupNames.stream().distinct().filter(Groups.ALL::contains).toList()) {
            addGroup(userId, name);
        }
    }

    /** Adds the user to the group when not already a member. */
    public void addGroup(UUID userId, String groupName) {
        jdbcClient.sql("""
                INSERT INTO user_groups (user_id, group_name)
                SELECT :id, :name WHERE NOT EXISTS
                    (SELECT 1 FROM user_groups WHERE user_id = :id AND group_name = :name)
                """).param("id", userId).param("name", groupName).update();
    }
}
