package com.elepay.security;

/** Runs a maker-checker proposal after a different operator has approved it. */
public interface ApprovedActionHandler {
    boolean supports(String actionType);

    void execute(MakerCheckerService.Proposal proposal);
}
