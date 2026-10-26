package io.datasync.server;

import io.datasync.core.config.DataSyncConfig.JobDef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobScheduleTest {

    @Test
    void defaultsToEveryFiveMinutesByCron() {
        JobSchedule s = JobSchedule.of(job(null, null));
        Instant at = LocalDateTime.of(2026, 10, 26, 10, 2, 30).atZone(ZoneId.systemDefault()).toInstant();
        assertThat(s.next(at)).isEqualTo(LocalDateTime.of(2026, 10, 26, 10, 5).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    void intervalTakesPrecedenceAndJitterStaysWithinItsShare() {
        JobSchedule s = JobSchedule.of(job("*/5 * * * *", "30s"));
        Instant at = Instant.parse("2026-10-26T10:00:00Z");
        assertThat(s.next(at)).isEqualTo(at.plusSeconds(30));
        for (int i = 0; i < 100; i++) assertThat(s.jitter(at)).isBetween(Duration.ZERO, Duration.ofSeconds(3));
    }

    @Test
    void rejectsBadSchedulesWithReadableMessages() {
        assertThatThrownBy(() -> JobSchedule.of(job(null, "5s"))).hasMessage("Run at most every 10 seconds");
        assertThatThrownBy(() -> JobSchedule.of(job(null, "five minutes"))).hasMessageContaining("must look like 30s, 5m or 2h");
        assertThatThrownBy(() -> JobSchedule.of(job("61 * * * *", null))).hasMessageStartingWith("Invalid cron expression '61 * * * *'");
    }

    private static JobDef job(String cron, String every) {
        return new JobDef("j", null, null, null, null, null, null, null, null, null, cron, every, null, null);
    }
}
