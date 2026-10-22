package io.datasync.core.config;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private static final String YAML = """
        connections:
          - name: oracle_prod
            kind: jdbc-oracle
            url: jdbc:oracle:thin:@//ora-host:1521/ORCLPDB1
            password: ${env:ORA_PASSWORD}
        jobs:
          - name: customers_sync
            source: { connection: oracle_prod, object: HR.CUSTOMERS_V }
            keys: [CUSTOMER_ID]
            columns: { include: "*", rename: { CUST_NAME: customer_name } }
            schedule: "*/5 * * * *"
            sinks:
              - type: jdbc
                connection: pg_dw
                target: public.customers
        """;

    @Test
    void loadsJobsWithDefaultsAndResolvesEnvReferences() throws Exception {
        DataSyncConfig config = ConfigLoader.load(new StringReader(YAML), Map.of("ORA_PASSWORD", "s3cr$t")::get);

        assertThat(config.connections().getFirst()).containsEntry("password", "s3cr$t");
        DataSyncConfig.JobDef job = config.jobs().getFirst();
        assertThat(job.mode()).isEqualTo(DataSyncConfig.Mode.SNAPSHOT_DIFF);
        assertThat(job.batchSize()).isEqualTo(10_000);
        assertThat(job.maxDeletePercent()).isEqualTo(20);
        assertThat(job.columns().include()).isEqualTo(List.of("*"));
        assertThat(job.columns().rename()).containsEntry("CUST_NAME", "customer_name");
        assertThat(job.sinks().getFirst()).containsEntry("target", "public.customers");
    }

    @Test
    void missingEnvVariableIsAnError() {
        assertThatThrownBy(() -> ConfigLoader.load(new StringReader(YAML), name -> null))
            .hasMessageContaining("ORA_PASSWORD");
    }
}
