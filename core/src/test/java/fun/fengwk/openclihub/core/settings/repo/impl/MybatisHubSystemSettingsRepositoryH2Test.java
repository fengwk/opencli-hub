package fun.fengwk.openclihub.core.settings.repo.impl;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.settings.repo.HubSystemSettingsRepository;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the singleton mapper and optimistic update SQL against H2. */
@SpringBootTest(classes = fun.fengwk.openclihub.core.CoreTestApplication.class)
class MybatisHubSystemSettingsRepositoryH2Test {

    @Autowired
    private HubSystemSettingsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRoundTripAndOptimisticallyUpdateSingleton() {
        jdbcTemplate.update("delete from hub_system_settings where id = 1");
        try {
            HubSystemSettings initial = new HubSystemSettings();
            initial.setProxyMode(HubProxyMode.DIRECT);
            initial.setVersion(0L);
            LocalDateTime seeded = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
            initial.setCreateTime(seeded);
            initial.setUpdateTime(seeded);
            assertThat(repository.add(initial)).isTrue();

            HubSystemSettings loaded = repository.find();
            assertThat(loaded.getProxyMode()).isEqualTo(HubProxyMode.DIRECT);
            assertThat(loaded.getVersion()).isZero();
            assertThat(loaded.getCreateTime()).isEqualTo(seeded);
            assertThat(loaded.getUpdateTime()).isEqualTo(seeded);

            LocalDateTime updatedAt = LocalDateTime.of(2026, 1, 2, 3, 5, 0);
            loaded.setProxyMode(HubProxyMode.CUSTOM);
            loaded.setProxyServer("http://proxy.example:8080");
            loaded.setUpdateTime(updatedAt);
            assertThat(repository.update(loaded, 0L)).isTrue();
            assertThat(repository.update(loaded, 0L)).isFalse();

            HubSystemSettings updated = repository.find();
            assertThat(updated.getProxyMode()).isEqualTo(HubProxyMode.CUSTOM);
            assertThat(updated.getProxyServer()).isEqualTo("http://proxy.example:8080");
            assertThat(updated.getVersion()).isEqualTo(1L);
            assertThat(updated.getUpdateTime()).isEqualTo(updatedAt);
            LocalDateTime modifiedColumn = jdbcTemplate.queryForObject(
                "select gmt_modified from hub_system_settings where id = 1",
                LocalDateTime.class);
            assertThat(modifiedColumn).isEqualTo(updatedAt);
        } finally {
            jdbcTemplate.update("delete from hub_system_settings where id = 1");
        }
    }

}
