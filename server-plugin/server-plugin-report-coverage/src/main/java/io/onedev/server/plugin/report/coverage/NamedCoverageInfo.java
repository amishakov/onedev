package io.onedev.server.plugin.report.coverage;

public class NamedCoverageInfo extends CoverageInfo {

	private static final long serialVersionUID = 1L;
	
	private final String name;
	
	public NamedCoverageInfo(String name, 
			int statementCoverage, int methodCoverage, 
			int branchCoverage, int lineCoverage) {
		super(statementCoverage, methodCoverage, branchCoverage, lineCoverage);
		this.name = name;
	}

	public String getName() {
		return name;
	}
	
}
