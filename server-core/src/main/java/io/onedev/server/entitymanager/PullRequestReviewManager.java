package io.onedev.server.entitymanager;

import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestReview;
import io.onedev.server.persistence.dao.EntityManager;

import javax.annotation.Nullable;
import java.util.Collection;

public interface PullRequestReviewManager extends EntityManager<PullRequestReview> {
	
    void review(PullRequest request, boolean approved, @Nullable String note);
	
	void populateReviews(Collection<PullRequest> requests);

    void createOrUpdate(PullRequestReview review);
	
}
