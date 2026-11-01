package fun.fengwk.openclihub.core.command.service;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;

class HubManagedOutputArgumentsTest {

    @Test
    void shouldManageCommonDirectoryOutputArgs() {
        assertThat(HubManagedOutputArguments.classify(arg("op", "Output directory"))).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(HubManagedOutputArguments.classify(arg("out", "Directory to save images"))).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(HubManagedOutputArguments.classify(arg("output", "输出目录"))).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(HubManagedOutputArguments.classify(arg("path", "Download directory"))).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
    }

    @Test
    void shouldManageFilePathOutputsAsFiles() {
        assertThat(HubManagedOutputArguments.classify(arg("output", "Output file path (default: /tmp/x.png)")))
            .isEqualTo(HubCommandOutputTargetType.FILE);
    }

    @Test
    void shouldIgnoreBusinessDestinationArgs() {
        assertThat(HubManagedOutputArguments.classify(arg("path", "Folder path to list (empty for root)"))).isNull();
        assertThat(HubManagedOutputArguments.classify(arg("to", "Arrival IATA code"))).isNull();
        assertThat(HubManagedOutputArguments.classify(arg("destination", "Destination keyword (city)"))).isNull();
    }

    @Test
    void shouldBuildSyntheticRuleAndHideFromCatalog() {
        OpenCliCommand command = new OpenCliCommand();
        command.setCommandKey("xiaohongshu/download");
        command.setArgs(List.of(
            arg("url", "Note url"),
            arg("output", "Output directory")));
        HubCommandOutputRule rule = HubManagedOutputArguments.syntheticRule(command);
        assertThat(rule).isNotNull();
        assertThat(rule.getArgumentName()).isEqualTo("output");
        assertThat(rule.getTargetType()).isEqualTo(HubCommandOutputTargetType.DIRECTORY);
        assertThat(HubManagedOutputArguments.shouldHideFromPublicCatalog(command.getArgs().get(1), null)).isTrue();
        assertThat(HubManagedOutputArguments.shouldHideFromPublicCatalog(command.getArgs().get(0), null)).isFalse();
    }

    private static OpenCliCommandArg arg(String name, String help) {
        OpenCliCommandArg arg = new OpenCliCommandArg();
        arg.setName(name);
        arg.setHelp(help);
        arg.setPositional(false);
        arg.setValueRequired(true);
        arg.setType("string");
        return arg;
    }
}
