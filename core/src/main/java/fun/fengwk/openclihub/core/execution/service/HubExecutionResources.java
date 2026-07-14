package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.core.command.validator.NormalizedOpenCliArgv;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import fun.fengwk.openclihub.core.resource.service.HubResourceLease;
import fun.fengwk.openclihub.core.resource.service.HubResourceLeaseManager;
import fun.fengwk.openclihub.core.resource.service.HubResourceService;
import fun.fengwk.openclihub.core.resource.util.HubResourcePaths;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resource orchestration dedicated to a single execution. Scans the normalized argv for
 * {@code /resources/...} tokens, acquires a shared lease for each (so concurrent deletes
 * are blocked while the OpenCLI process is running), creates the per-execution output
 * group on demand when an output rule is configured, and resolves the argv tokens to
 * their real on-disk paths so the caller never passes a virtual path to OpenCLI.
 *
 * <p>The lifecycle is try-with-resources: {@link ResourceContext#close()} releases every
 * input lease exactly once, in the order they were acquired.
 *
 * @author fengwk
 */
@Component
public class HubExecutionResources {

    private final HubResourceService resourceService;
    private final HubResourceLeaseManager leaseManager;
    private final Path resourceRoot;

    public HubExecutionResources(HubResourceService resourceService,
                                 HubResourceLeaseManager leaseManager,
                                 OpenCliHubProperties properties) {
        if (resourceService == null) {
            throw new IllegalArgumentException("resourceService must not be null");
        }
        if (leaseManager == null) {
            throw new IllegalArgumentException("leaseManager must not be null");
        }
        this.resourceService = resourceService;
        this.leaseManager = leaseManager;
        this.resourceRoot = HubResourcePaths.resourceRoot(properties);
    }

    /**
     * Prepare the per-execution resource context. Returns a closeable
     * {@link ResourceContext} that the execution service hands to the worker; the worker
     * MUST close it (try-with-resources) so the input leases are released even on
     * failure.
     *
     * <p>The output group is only created when {@code outputRule} is non-null. When
     * {@code outputRule} is null but the user invoked with virtual-path argv tokens, the
     * group is left null and the substituted argv contains only the real paths of the
     * inputs.
     */
    public ResourceContext prepare(String executionId,
                                   NormalizedOpenCliArgv normalized,
                                   HubCommandOutputRule outputRule) {
        if (normalized == null) {
            throw new IllegalArgumentException("normalized must not be null");
        }
        if (executionId == null || executionId.isBlank()) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable("execution id must not be blank");
        }

        List<HubResourceLease> leases = new ArrayList<>();
        List<String> substitutedArgv;
        try {
            substitutedArgv = substituteInputs(executionId, leases, normalized.getNormalizedArgv());
        } catch (RuntimeException ex) {
            releaseLeases(leases);
            throw ex;
        }

        HubExecutionResourceGroup group = null;
        if (outputRule != null) {
            // Create the output group after all input leases are held. A create failure
            // releases every lease acquired while substituting earlier argv tokens.
            group = createOutputGroupSafely(executionId, leases);
        }

        return new ResourceContext(substitutedArgv, group, leases);
    }

    private List<String> substituteInputs(String executionId,
                                          List<HubResourceLease> leases,
                                          List<String> argv) {
        List<String> substituted = new ArrayList<>(argv.size());
        for (String token : argv) {
            if (isResourceVirtualPath(token)) {
                // resourceService.resolve throws the correct domain code on invalid paths;
                // we surface the typed error so the caller can produce a terminal FAILED DTO.
                Path real = resourceService.resolve(token).getRealPath();
                if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS)) {
                    throw HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable(
                        "Resource virtual path not found: " + token);
                }
                HubResourceLease lease = leaseManager.acquire(real,
                    "input-exec-" + executionId);
                leases.add(lease);
                substituted.add(real.toString());
            } else {
                substituted.add(token);
            }
        }
        return substituted;
    }

    private HubExecutionResourceGroup createOutputGroupSafely(
        String executionId, List<HubResourceLease> leases) {
        try {
            return resourceService.createExecutionGroup(executionId, null);
        } catch (RuntimeException ex) {
            releaseLeases(leases);
            throw ex;
        }
    }

    /**
     * Scan the output group for files; returns an empty list when {@code group} is null.
     * Scan failures propagate so the execution service can surface a terminal FAILED DTO
     * instead of a misleading empty list.
     */
    public List<HubResourceItemDTO> scan(HubExecutionResourceGroup group) {
        if (group == null) {
            return List.of();
        }
        return resourceService.scanExecutionGroup(group);
    }

    /**
     * Finds a persisted execution group by its globally unique execution id. Resource
     * dates are UTC and are not stored on the execution row, so detail reads inspect only
     * the root's date-directory level instead of guessing from the server timezone.
     */
    public List<HubResourceItemDTO> scanExisting(String executionId) {
        String groupName = HubResourcePaths.executionGroup(executionId);
        if (!Files.exists(resourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.isSymbolicLink(resourceRoot)
            || !Files.isDirectory(resourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        try (var children = Files.list(resourceRoot)) {
            List<Path> dateDirs = children
                .filter(path -> !Files.isSymbolicLink(path))
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .sorted()
                .toList();
            for (Path dateDir : dateDirs) {
                LocalDate date;
                try {
                    date = HubResourcePaths.parseDate(dateDir.getFileName().toString());
                } catch (RuntimeException ignored) {
                    continue;
                }
                Path groupPath = HubResourcePaths.groupDir(resourceRoot, date, groupName);
                if (!Files.exists(groupPath, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(groupPath)
                    || !Files.isDirectory(groupPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
                }
                return scan(HubExecutionResourceGroup.builder()
                    .executionId(executionId)
                    .date(date)
                    .group(groupName)
                    .realPath(groupPath)
                    .build());
            }
            return List.of();
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_DELETE_FAILED.asThrowable(ex);
        }
    }

    /**
     * Remove the group only when no files were produced; used by the execution service to
     * keep the resource centre tidy without requiring admin cleanup.
     */
    public void removeGroupIfEmpty(HubExecutionResourceGroup group) {
        if (group == null) {
            return;
        }
        try {
            resourceService.removeExecutionGroupIfEmpty(group);
        } catch (RuntimeException ex) {
            // best-effort: failure to prune an empty group is not fatal
        }
    }

    private static boolean isResourceVirtualPath(String token) {
        if (token == null) {
            return false;
        }
        String normalized = token.replace('\\', '/');
        return normalized.startsWith(HubResourcePaths.VIRTUAL_PREFIX);
    }

    private static void releaseLeases(List<HubResourceLease> leases) {
        if (leases == null) {
            return;
        }
        for (HubResourceLease lease : leases) {
            try {
                lease.close();
            } catch (RuntimeException ignored) {
                // best-effort release; manager tolerates over-release
            }
        }
    }

    /**
     * Lifecycle holder for the prepared execution resources. {@link #close()} releases
     * every input lease exactly once.
     */
    public static final class ResourceContext implements AutoCloseable {

        private final List<String> substitutedArgv;
        private final HubExecutionResourceGroup group;
        private final List<HubResourceLease> inputLeases;
        private boolean closed;

        ResourceContext(List<String> substitutedArgv,
                        HubExecutionResourceGroup group,
                        List<HubResourceLease> inputLeases) {
            this.substitutedArgv = List.copyOf(substitutedArgv);
            this.group = group;
            this.inputLeases = List.copyOf(inputLeases);
        }

        public List<String> getSubstitutedArgv() {
            return substitutedArgv;
        }

        public HubExecutionResourceGroup getGroup() {
            return group;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseLeases(inputLeases);
        }

    }

}
