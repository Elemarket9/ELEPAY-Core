package com.elepay.merchanttools.payroll;

import com.elepay.PostgresTestBase;
import com.elepay.TestApi;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Payroll can only be marked as paid by a real payout batch of this merchant that matches the run. */
class PayrollApiIT extends PostgresTestBase {
    @LocalServerPort int port;
    @Autowired JdbcClient db;

    @Test
    @DisplayName("An invented payout reference cannot mark payroll as paid; other merchants cannot see payroll")
    void payrollCannotBeMarkedPaidWithoutARealPayout() {
        TestApi api = new TestApi(port);
        String s = UUID.randomUUID().toString().substring(0, 8);
        String ops = api.token("ops-" + s, "ops");
        String owner = api.token("boss-" + s);
        UUID merchant = UUID.fromString(api.call("POST", "/v1/merchants", owner, null, Map.of("legalName", "Shop " + s,
                "tradingName", "Shop " + s, "kind", "merchant", "contactPhone", "0241234567")).body().get("id").asString());
        api.call("POST", "/v1/ops/merchants/" + merchant + "/review", ops, null, Map.of("decision", "approve"));

        var emp = api.call("POST", "/v1/payroll/employees", owner, null, Map.of("merchantId", merchant.toString(), "fullName", "Yaw",
                "momoNetwork", "MTN", "momoNumber", "0244555666", "baseSalaryPesewas", 250_000));
        assertThat(emp.status()).isEqualTo(200);
        var noDestination = api.call("POST", "/v1/payroll/employees", owner, null, Map.of("merchantId", merchant.toString(),
                "fullName", "Esi", "baseSalaryPesewas", 250_000));
        assertThat(noDestination.status()).as("employees need somewhere to be paid").isEqualTo(400);

        var run = api.call("POST", "/v1/payroll/runs", owner, null, Map.of("merchantId", merchant.toString(), "month", 10, "year", 2026));
        assertThat(run.status()).isEqualTo(200);
        String runId = run.body().get("id").asString();
        assertThat(api.call("POST", "/v1/payroll/runs", owner, null, Map.of("merchantId", merchant.toString(), "month", 10, "year", 2026)).status())
                .as("one payroll per month").isEqualTo(409);
        assertThat(api.call("POST", "/v1/payroll/runs/" + runId + "/approve", owner, null, null).status())
                .as("cannot approve before submission").isEqualTo(409);
        assertThat(api.call("POST", "/v1/payroll/runs/" + runId + "/submit", owner, null, null).status()).isEqualTo(200);
        assertThat(api.call("POST", "/v1/payroll/runs/" + runId + "/approve", owner, null, null).status())
                .as("submitter cannot self-approve payroll run").isEqualTo(403);
        String checkerSub = "checker-" + s;
        db.sql("INSERT INTO merchant_member (merchant_id, subject, role) VALUES (:m, :sub, 'owner')")
                .param("m", merchant).param("sub", checkerSub).update();
        String checker = api.token(checkerSub);
        assertThat(api.call("POST", "/v1/payroll/runs/" + runId + "/approve", checker, null, null).status()).isEqualTo(200);

        var fake = api.call("POST", "/v1/payroll/runs/" + runId + "/payout", owner, null, Map.of("payoutBatchReference", "PAYOUT-PR-123456"));
        assertThat(fake.status()).isEqualTo(422);
        assertThat(db.sql("SELECT status FROM payroll_run WHERE id = :id").param("id", UUID.fromString(runId)).query(String.class).single())
                .isEqualTo("APPROVED");
        assertThat(db.sql("SELECT count(*) FROM payroll_payslip WHERE payroll_run_id = :id AND payment_status <> 'PENDING'")
                .param("id", UUID.fromString(runId)).query(Long.class).single()).isZero();

        String stranger = api.token("stranger-" + s);
        assertThat(api.call("GET", "/v1/payroll/runs/" + runId + "/payslips", stranger, null, null).status()).isEqualTo(404);
        assertThat(api.call("GET", "/v1/payroll/employees/merchant/" + merchant, stranger, null, null).status()).isEqualTo(404);
    }
}
