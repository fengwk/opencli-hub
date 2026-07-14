package fun.fengwk.openclihub.core.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.settings.repo.HubSystemSettingsRepository;
import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import fun.fengwk.openclihub.share.model.settings.HubSystemSettingsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests lazy singleton creation, normalization, and optimistic updates. */
class HubSystemSettingsServiceImplTest {

    private HubSystemSettingsRepository repository;
    private HubSystemSettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(HubSystemSettingsRepository.class);
        service = new HubSystemSettingsServiceImpl(repository);
    }

    @Test
    void shouldCreateDirectDefaultsWhenSingletonIsAbsent() {
        when(repository.find()).thenReturn(null);
        when(repository.add(any())).thenReturn(true);

        HubSystemSettings settings = service.get();

        assertThat(settings.getProxyMode()).isEqualTo(HubProxyMode.DIRECT);
        assertThat(settings.getProxyServer()).isNull();
        verify(repository).add(any());
    }

    @Test
    void shouldNormalizeAndPersistCustomProxy() {
        HubSystemSettings current = settings(HubProxyMode.DIRECT, null, 3L);
        when(repository.find()).thenReturn(current);
        when(repository.update(any(), eq(3L))).thenReturn(true);
        HubSystemSettingsDTO request = new HubSystemSettingsDTO();
        request.setProxyMode(HubProxyMode.CUSTOM);
        request.setProxyServer(" HTTPS://Proxy.Example:8443 ");

        HubSystemSettings updated = service.update(request);

        assertThat(updated.getProxyMode()).isEqualTo(HubProxyMode.CUSTOM);
        assertThat(updated.getProxyServer()).isEqualTo("https://proxy.example:8443");
        assertThat(updated.getVersion()).isEqualTo(4L);
        ArgumentCaptor<HubSystemSettings> captor = ArgumentCaptor.forClass(HubSystemSettings.class);
        verify(repository).update(captor.capture(), eq(3L));
        assertThat(captor.getValue().getProxyServer()).isEqualTo("https://proxy.example:8443");
    }

    @Test
    void shouldRejectGlobalInheritBeforeRepositoryAccess() {
        HubSystemSettingsDTO request = new HubSystemSettingsDTO();
        request.setProxyMode(HubProxyMode.INHERIT);

        assertThatThrownBy(() -> service.update(request))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.SETTINGS_ARGUMENT_INVALID));
        verify(repository, never()).find();
    }

    @Test
    void shouldReportConflictAfterTwoOptimisticUpdateFailures() {
        when(repository.find())
            .thenReturn(settings(HubProxyMode.DIRECT, null, 1L))
            .thenReturn(settings(HubProxyMode.DIRECT, null, 2L));
        when(repository.update(any(), eq(1L))).thenReturn(false);
        when(repository.update(any(), eq(2L))).thenReturn(false);
        HubSystemSettingsDTO request = new HubSystemSettingsDTO();
        request.setProxyMode(HubProxyMode.DIRECT);

        assertThatThrownBy(() -> service.update(request))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .extracting("code")
            .isEqualTo(prefixed(HubErrorCodes.SETTINGS_UPDATE_CONFLICT));
    }

    private static HubSystemSettings settings(HubProxyMode mode, String server, long version) {
        HubSystemSettings settings = new HubSystemSettings();
        settings.setProxyMode(mode);
        settings.setProxyServer(server);
        settings.setVersion(version);
        return settings;
    }

    private static String prefixed(HubErrorCodes code) {
        return code.getDomain() + "." + code.name();
    }

}
