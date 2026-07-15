package fun.fengwk.openclihub.core.settings.repo.impl;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.settings.repo.HubSystemSettingsRepository;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the singleton mapper and optimistic update SQL against H2. */
@SpringBootTest(classes = fun.fengwk.openclihub.core.CoreTestApplication.class)
class MysqlHubSystemSettingsRepositoryH2Test {

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
            assertThat(repository.add(initial)).isTrue();

            HubSystemSettings loaded = repository.find();
            assertThat(loaded.getProxyMode()).isEqualTo(HubProxyMode.DIRECT);
            assertThat(loaded.getVersion()).isZero();

            loaded.setProxyMode(HubProxyMode.CUSTOM);
            loaded.setProxyServer("http://proxy.example:8080");
            assertThat(repository.update(loaded, 0L)).isTrue();
            assertThat(repository.update(loaded, 0L)).isFalse();

            HubSystemSettings updated = repository.find();
            assertThat(updated.getProxyMode()).isEqualTo(HubProxyMode.CUSTOM);
            assertThat(updated.getProxyServer()).isEqualTo("http://proxy.example:8080");
            assertThat(updated.getVersion()).isEqualTo(1L);
        } finally {
            jdbcTemplate.update("delete from hub_system_settings where id = 1");
        }
    }

}
