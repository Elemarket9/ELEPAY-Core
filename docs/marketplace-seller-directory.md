# Marketplace-managed sellers

Use a server-side marketplace credential with the explicit seller_sync scope to POST an array to /v1/merchants/{marketplace}/seller-directory/sync. A marketplace owner or admin can also sync. Each request accepts 1–500 sellers. Each seller commits independently and results follow input order. Repeating or concurrently submitting a seller ID returns the same ELEPAY identity within that marketplace.

    [{"externalSellerId":"vendor-42","profile":{"kind":"merchant","legalName":"Example Trading Ltd","tradingName":"Example Trading","contactPhone":"0241234567"},"evidenceReference":"partner-review-42"}]

New managed merchants begin in pending_review. Operators can configure elepay.onboarding.trusted-marketplaces with a comma-separated list of marketplace UUIDs authorized to attest completed seller checks under their onboarding agreement. Only configure this after agreeing what evidence the partner must retain. For these marketplaces a nonblank evidence reference attests eligibility. An external seller ID by itself cannot activate a merchant. Resync a managed profile to update its trading name or submit the attestation. Legal identity and contact changes require review.

For an existing independent ELEPAY merchant, send externalSellerId and merchantCode instead of profile. The merchant accepts through the existing invitation API. A marketplace cannot manage an independent merchant's payout details through the directory.

Read /v1/merchants/{marketplace}/seller-directory/{externalSellerId} with view scope for merchant ID, relationship/merchant status and payout destination readiness. For active managed sellers submit destinations at the same path plus /destinations. Use /destinations/{destination}/confirm and /resend for seller confirmation. Name verification, confirmation attempt limits and cooling-off rules use the existing destination workflow.

Pass externalSellerId in place of merchantId on each synchronous or asynchronous checkout allocation. Sync new sellers before checkout. Existing activity and relationship checks still apply. Payout destination readiness describes a destination verified for payout, independently of checkout eligibility.
