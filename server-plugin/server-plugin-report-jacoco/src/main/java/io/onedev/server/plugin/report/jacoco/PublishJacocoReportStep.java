package io.onedev.server.plugin.report.jacoco;

import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.OneDev;
import io.onedev.server.annotation.Editable;
import io.onedev.server.annotation.Interpolative;
import io.onedev.server.annotation.Patterns;
import io.onedev.server.buildspec.BuildSpec;
import io.onedev.server.buildspec.step.StepGroup;
import io.onedev.server.codequality.CoverageStatus;
import io.onedev.server.model.Build;
import io.onedev.server.plugin.report.coverage.*;
import io.onedev.server.search.code.CodeSearchManager;
import io.onedev.server.util.XmlUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import javax.validation.constraints.NotEmpty;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Editable(order=9920, group=StepGroup.PUBLISH_REPORTS, name="JaCoCo Coverage")
public class PublishJacocoReportStep extends PublishCoverageReportStep {

	private static final long serialVersionUID = 1L;
	
	@Editable(order=100, description="Specify JaCoCo coverage xml report file relative to <a href='https://docs.onedev.io/concepts#job-workspace'>job workspace</a>, "
			+ "for instance, <tt>target/site/jacoco/jacoco.xml</tt>. Use * or ? for pattern match")
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
	public boolean requireCommitIndex() {
		return true;
	}

	@Override
	protected CoverageReport createReport(Build build, File inputDir, File reportDir, TaskLogger logger) {
		int baseLen = inputDir.getAbsolutePath().length() + 1;
		SAXReader reader = new SAXReader();
		XmlUtils.disallowDocTypeDecl(reader);

		List<CategoryCoverageInfo> packageCoverages = new ArrayList<>();
		var totalAndCoveredInfo = new TotalAndCoveredInfo(0, 0, 0, 0, 0, 0, 0, 0);
		
		CodeSearchManager searchManager = OneDev.getInstance(CodeSearchManager.class);
		
		for (File file: getPatternSet().listFiles(inputDir)) {
			String relativePath = file.getAbsolutePath().substring(baseLen);
			logger.log("Processing JaCoCo report '" + relativePath + "'...");
			Document doc;
			try {
				String xml = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
				doc = reader.read(new StringReader(XmlUtils.stripDoctype(xml)));
				for (Element packageElement: doc.getRootElement().elements("package")) {
					String packageName = packageElement.attributeValue("name");
					var packageTotalAndCoveredInfo = getTotalAndCoveredInfo(packageElement);
					List<ItemCoverageInfo> fileCoverages = new ArrayList<>();
					
					for (Element fileElement: packageElement.elements("sourcefile")) {
						String fileName = fileElement.attributeValue("name");
						var fileTotalAndCoveredInfo = getTotalAndCoveredInfo(fileElement);
						String blobPath = searchManager.findBlobPathBySuffix(build.getProject(), build.getCommitId(), 
								packageName + "/" + fileName);
						if (blobPath != null) {
							fileCoverages.add(new ItemCoverageInfo(fileName, fileTotalAndCoveredInfo.getCoverageInfo(), blobPath));
							Map<Integer, CoverageStatus> lineCoverages = new HashMap<>();
							for (Element lineElement: fileElement.elements("line")) {
								int lineNum = Integer.parseInt(lineElement.attributeValue("nr")) - 1;
								CoverageStatus coverageStatus;
								int mi = Integer.parseInt(lineElement.attributeValue("mi"));
								int ci = Integer.parseInt(lineElement.attributeValue("ci"));
								int mb = Integer.parseInt(lineElement.attributeValue("mb"));
								int cb = Integer.parseInt(lineElement.attributeValue("cb"));
								if (mi == 0 && mb == 0)
									coverageStatus = CoverageStatus.COVERED;
								else if (ci == 0 && cb == 0)
									coverageStatus = CoverageStatus.NOT_COVERED;
								else
									coverageStatus = CoverageStatus.PARTIALLY_COVERED;
								lineCoverages.put(lineNum, coverageStatus);
							}
							writeLineCoverages(build, blobPath, lineCoverages);
						} else {
							logger.warning("Can not map file '" + fileName + "' under package '" 
									+ packageName + "' to blob path, ignoring coverage info...");
						}
					}
					
					packageCoverages.add(new CategoryCoverageInfo(packageName, packageTotalAndCoveredInfo.getCoverageInfo(), fileCoverages));
				}
				totalAndCoveredInfo = totalAndCoveredInfo.mergeWith(getTotalAndCoveredInfo(doc.getRootElement()));
			} catch (DocumentException e) {
				logger.warning("Ignored clover report '" + relativePath + "' as it is not a valid XML");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		if (!packageCoverages.isEmpty()) 
			return new CoverageReport(totalAndCoveredInfo.getCoverageInfo(), packageCoverages);
		else 
			return null;
	}
	
	private TotalAndCoveredInfo getTotalAndCoveredInfo(Element element) {
		int totalStatements = 0;
		int coveredStatements = 0;
		int totalMethods = 0;
		int coveredMethods = 0;
		int totalBranches = 0;
		int coveredBranches = 0;
		int totalLines = 0;
		int coveredLines = 0;
		for (Element counterElement: element.elements("counter")) {
			int covered = Integer.parseInt(counterElement.attributeValue("covered"));
			int total = covered + Integer.parseInt(counterElement.attributeValue("missed"));
			switch (counterElement.attributeValue("type")) {
				case "INSTRUCTION":
					totalStatements = total;
					coveredStatements = covered;
					break;
				case "METHOD":
					totalMethods = total;
					coveredMethods = covered;
					break;
				case "BRANCH":
					totalBranches = total;
					coveredBranches = covered;
					break;
				case "LINE":
					totalLines = total;
					coveredLines = covered;
					break;
			}
		}
		return new TotalAndCoveredInfo(
				totalStatements, coveredStatements, 
				totalMethods, coveredMethods, 
				totalBranches, coveredBranches, 
				totalLines, coveredLines);
	}

}
