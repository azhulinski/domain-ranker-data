package com.domainranker.models

import java.time.Instant

case class TrustpilotReview(
  id: String,
  domain: String,
  category: String,
  reviewText: String,
  reviewDate: Instant,
  sentimentScore: Option[Double] = None
)

case class DomainTraffic(
  domain: String,
  traffic: Long,
  lastUpdated: Instant
)

case class DomainSummary(
  domain: String,
  latestReview: Option[TrustpilotReview],
  recentReviewCount: Int,
  totalReviewCount: Int,
  traffic: Option[Long],
  sentimentSumRecentReviews: Double,
  domainAge: Long = 0,  // Domain age in days
  rankScore: Double = 0.0
)