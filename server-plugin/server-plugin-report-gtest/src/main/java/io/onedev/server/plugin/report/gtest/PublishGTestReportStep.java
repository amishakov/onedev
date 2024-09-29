package io.onedev.server.plugin.report.gtest;

import com.google.common.collect.Lists;
import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.annotation.Editable;
import io.onedev.server.annotation.Interpolative;
import io.onedev.server.annotation.Patterns;
import io.onedev.server.buildspec.BuildSpec;
import io.onedev.server.buildspec.step.StepGroup;
import io.onedev.server.model.Build;
import io.onedev.server.plugin.report.unittest.PublishUnitTestReportStep;
import io.onedev.server.plugin.report.unittest.UnitTestReport;
import io.onedev.server.plugin.report.unittest.UnitTestReport.TestCase;
import io.onedev.server.util.XmlUtils;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;

import javax.validation.constraints.NotEmpty;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Editable(order=10000, group=StepGroup.PUBLISH, name="Google Test Report")
public class PublishGTestReportStep extends PublishUnitTestReportStep {

	private static final long serialVersionUID = 1L;

	@Editable(order=100, description="Specify GoogleTest XML result file relative to <a href='https://docs.onedev.io/concepts#job-workspace'>job workspace</a>. " +
			"This report can be generated with environment variable <tt>GTEST_OUTPUT</tt> when running tests, " +
			"For instance, <code>export GTEST_OUTPUT=&quot;xml:gtest-result.xml&quot;</code>. " +
			"Use * or ? for pattern match")
	@Interpolative(variableSuggester="suggestVariables")
	@Patterns(path=true)
	@NotEmpty
	@Override
	public String getFilePatterns() {
		return super.getFilePatterns();
	}

	@Override
	public void setFilePatterns(String filePatterns) {
		super.setFilePatterns(filePatterns);
	}
	
	@SuppressWarnings("unused")
	private static List<InputSuggestion> suggestVariables(String matchWith) {
		return BuildSpec.suggestVariables(matchWith, true, true, false);
	}

	@Override
	protected UnitTestReport process(Build build, File inputDir, TaskLogger logger) {
		SAXReader reader = new SAXReader();
		XmlUtils.disallowDocTypeDecl(reader);
		
		List<TestCase> testCases = new ArrayList<>();
		int baseLen = inputDir.getAbsolutePath().length()+1;
		for (File file: FileUtils.listFiles(inputDir, Lists.newArrayList("**"), Lists.newArrayList())) {
			String relativePath = file.getAbsolutePath().substring(baseLen);
			logger.log("Processing GoogleTest report '" + relativePath + "'...");
			try {
				String xml = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
				xml = XmlUtils.stripDoctype(xml);
				testCases.addAll(GTestReportParser.parse(build, reader.read(new StringReader(xml))));
			} catch (DocumentException e) {
				logger.warning("Ignored test report '" + relativePath + "' as it is not a valid XML");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		if (!testCases.isEmpty()) 
			return new UnitTestReport(testCases, true);
		else 
			return null;
	}

}
