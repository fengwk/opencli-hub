package fun.fengwk.openclihub.core.settings.repo;

import fun.fengwk.openclihub.core.settings.service.model.HubSystemSettings;

/**
 * Persistence boundary for the id=1 system settings singleton.
 *
 * @author fengwk
 */
public interface HubSystemSettingsRepository {

    HubSystemSettings find();

    boolean add(HubSystemSettings settings);

    boolean update(HubSystemSettings settings, long expectedVersion);

}
