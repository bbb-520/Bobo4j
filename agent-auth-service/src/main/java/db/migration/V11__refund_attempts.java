package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.util.*;

/** Preserve every confirmed failed attempt while keeping per-order request idempotency. */
public class V11__refund_attempts extends BaseJavaMigration {
    @Override public void migrate(Context context) throws SQLException { migrate(context.getConnection()); }
    public void migrate(Connection connection) throws SQLException {
        try(var sql=connection.createStatement()) {
            sql.execute("ALTER TABLE payment_refund ADD COLUMN attempt_no INT NOT NULL DEFAULT 1");
            sql.execute("ALTER TABLE payment_refund ADD COLUMN request_key VARCHAR(64) NULL");
            sql.execute("CREATE UNIQUE INDEX uk_refund_attempt ON payment_refund(order_id,attempt_no)");
            sql.execute("CREATE UNIQUE INDEX uk_refund_request ON payment_refund(order_id,request_key)");
            if(connection.getMetaData().getDatabaseProductName().equals("H2")) {
                sql.execute("ALTER TABLE payment_refund DROP CONSTRAINT fk_refund_order");
                var constraints=new ArrayList<String>();
                try(var rows=sql.executeQuery("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME='PAYMENT_REFUND' AND CONSTRAINT_TYPE='UNIQUE'")) {
                    while(rows.next())constraints.add(rows.getString(1));
                }
                // V8 has exactly one UNIQUE constraint (order_id); the two new keys are indexes.
                for(String constraint:constraints)sql.execute("ALTER TABLE payment_refund DROP CONSTRAINT \""+constraint.replace("\"","\"\"")+"\"");
                sql.execute("ALTER TABLE payment_refund ADD CONSTRAINT fk_refund_order FOREIGN KEY(order_id) REFERENCES payment_order(id)");
            } else {
                // MySQL names the unnamed V8 UNIQUE order_id index after its column.
                sql.execute("ALTER TABLE payment_refund DROP INDEX order_id");
            }
        }
    }
}
