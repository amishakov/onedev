package io.onedev.license;

import io.onedev.server.annotation.Editable;

import javax.validation.constraints.Min;

@Editable(order=50, name="Trial Subscription")
public class TrialSubscription extends LicensePayload {

	private static final long serialVersionUID = 1L;
	
	private String licenseGroup;
	
	private int trialDays = 30;

	private boolean checkUUID;

	@Editable(order=50)
	public String getLicenseGroup() {
		return licenseGroup;
	}

	public void setLicenseGroup(String licenseGroup) {
		this.licenseGroup = licenseGroup;
	}

	@Editable(order=100)
	@Min(1)
	public int getTrialDays() {
		return trialDays;
	}

	public void setTrialDays(int trialDays) {
		this.trialDays = trialDays;
	}

	public boolean isCheckUUID() {
		return checkUUID;
	}

	public void setCheckUUID(boolean checkUUID) {
		this.checkUUID = checkUUID;
	}
}
