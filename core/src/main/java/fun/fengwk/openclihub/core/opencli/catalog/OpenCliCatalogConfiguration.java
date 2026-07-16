package fun.fengwk.openclihub.core.opencli.catalog;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.command.repo.HubCommandBlacklistRepository;
import fun.fengwk.openclihub.core.command.repo.HubCommandOutputRuleRepository;
import fun.fengwk.openclihub.core.command.service.HubCommandBlacklistService;
import fun.fengwk.openclihub.core.command.service.HubCommandOutputRuleService;
import fun.fengwk.openclihub.core.command.service.HubCommandQueryService;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgvValidator;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the command subsystem.
 *
 * <p>The catalog default bean shells out to the pinned {@code opencli list -f json};
 * tests can override it with a {@link FileOpenCliCatalogSource} pointing at a local
 * {@code cli-manifest.json}. Service beans are wired with their repositories and the
 * catalog so that validators and assemblers share one source of truth.
 *
 * @author fengwk
 */
@Slf4j
@Configuration
public class OpenCliCatalogConfiguration {

    /**
     * Production catalog bean. Tests should override this with a file-source based
     * catalog so they do not depend on the pinned binary being installed on the agent.
     */
    @Bean
    @Qualifier("processBuilderOpenCliCommandCatalog")
    public OpenCliCommandCatalog processBuilderOpenCliCommandCatalog(OpenCliHubProperties properties) {
        log.info("Initializing OpenCLI catalog from binary {} (workdir={})",
            properties.getOpencli().getBinary(), properties.getOpencli().getWorkdir());
        OpenCliCatalogSource source = new ProcessBuilderOpenCliCatalogSource(properties);
        return new DefaultOpenCliCommandCatalog(source);
    }

    @Bean
    public HubCommandBlacklistService hubCommandBlacklistService(
        HubCommandBlacklistRepository repository) {
        return new HubCommandBlacklistService(repository);
    }

    @Bean
    public HubCommandOutputRuleService hubCommandOutputRuleService(
        HubCommandOutputRuleRepository repository, OpenCliCommandCatalog catalog) {
        return new HubCommandOutputRuleService(repository, catalog);
    }

    @Bean
    public OpenCliArgvValidator openCliArgvValidator(OpenCliCommandCatalog catalog) {
        return new OpenCliArgvValidator(catalog);
    }

    @Bean
    public HubCommandQueryService hubCommandQueryService(
        OpenCliCommandCatalog catalog,
        HubCommandBlacklistService blacklistService,
        HubCommandOutputRuleService outputRuleService) {
        return new HubCommandQueryService(catalog, blacklistService, outputRuleService);
    }

}
