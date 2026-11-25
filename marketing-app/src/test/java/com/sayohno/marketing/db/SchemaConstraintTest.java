package com.sayohno.marketing.db;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SchemaConstraintTest {

    @Test
    void emptyMysqlKeepsReceiveCountShortLinkAndMoneyPrecision() throws Exception {
        assumeTrue(dockerAvailable(), "Docker 不可用，MySQL 约束测试未执行");
        try (MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))) {
            mysql.start();
            try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 Statement statement = connection.createStatement()) {
                for (String file : new String[]{
                        "/db/V001__init.sql",
                        "/db/V002__claim_outbox_task.sql",
                        "/db/V003__claim_lease.sql",
                        "/db/V004__campaign.sql",
                        "/db/V005__allocation_main_link.sql",
                        "/db/V006__campaign_operations.sql",
                        "/db/V007__link_name.sql",
                        "/db/V008__simple_workspace_activity.sql",
                        "/db/V009__refund_amount_attendance.sql",
                        "/db/V010__external_link_activity.sql",
                        "/db/V011__campaign_create_request.sql",
                        "/db/V012__offer_domain_rename.sql",
                        "/db/V013__offer_enum_and_policy_keys.sql",
                        "/db/V014__rename_inventory_claim_limit.sql",
                        "/db/V015__link_access_channel_code.sql",
                        "/db/V016__grant_batch_payload_and_distributed_lock.sql"
                }) {
                    String sql = new String(SchemaConstraintTest.class.getResourceAsStream(file).readAllBytes(), StandardCharsets.UTF_8);
                    for (String part : sql.replaceAll("(?m)^--.*$", "").split(";")) {
                        if (!part.isBlank()) {
                            statement.execute(part);
                        }
                    }
                }
                assertEquals(2, scale(statement));
                statement.execute("INSERT INTO iam_user (id, username, password_hash, status, create_time, update_time) VALUES (1, 'ada', 'hash', 1, NOW(), NOW())");
                assertThrows(SQLException.class, () -> statement.execute("INSERT INTO iam_user (id, username, password_hash, status, create_time, update_time) VALUES (2, 'ada', 'hash', 1, NOW(), NOW())"));
                statement.execute("INSERT INTO issued_voucher (id, user_id, plan_id, claim_seq, issuer_scope, granted_at, usable_from, usable_until, status, create_time, update_time) VALUES (10, 7, 8, 1, 'merchant', NOW(), NOW(), NOW(), 'available', NOW(), NOW())");
                statement.execute("INSERT INTO issued_voucher (id, user_id, plan_id, claim_seq, issuer_scope, granted_at, usable_from, usable_until, status, create_time, update_time) VALUES (11, 7, 8, 2, 'merchant', NOW(), NOW(), NOW(), 'available', NOW(), NOW())");
                assertThrows(SQLException.class, () -> statement.execute("INSERT INTO issued_voucher (id, user_id, plan_id, claim_seq, issuer_scope, granted_at, usable_from, usable_until, status, create_time, update_time) VALUES (12, 7, 8, 1, 'merchant', NOW(), NOW(), NOW(), 'available', NOW(), NOW())"));
                statement.execute("INSERT INTO link (id, merchant_id, gid, domain, short_uri, full_short_url, origin_url, enable_status, del_time, create_time, update_time) VALUES (20, 1, 'g', 'nurl.ink', 'abc', 'nurl.ink/abc', 'https://example.com', 0, 0, NOW(), NOW())");
                assertThrows(SQLException.class, () -> statement.execute("INSERT INTO link (id, merchant_id, gid, domain, short_uri, full_short_url, origin_url, enable_status, del_time, create_time, update_time) VALUES (21, 1, 'g', 'nurl.ink', 'abc', 'nurl.ink/abc', 'https://example.com', 0, 0, NOW(), NOW())"));
                statement.execute("INSERT INTO link (id, merchant_id, gid, domain, short_uri, full_short_url, origin_url, enable_status, del_time, create_time, update_time) VALUES (22, 1, 'g', 'nurl.ink', 'abc', 'nurl.ink/abc', 'https://example.com', 0, 99, NOW(), NOW())");
            }
        }
    }

    private static int scale(Statement statement) throws SQLException {
        try (ResultSet keys = statement.executeQuery("""
                SELECT NUMERIC_SCALE FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'voucher_redemption' AND COLUMN_NAME = 'discount_amount'
                """)) {
            keys.next();
            return keys.getInt(1);
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
