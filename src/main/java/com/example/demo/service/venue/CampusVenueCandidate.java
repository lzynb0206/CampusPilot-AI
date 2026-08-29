package com.example.demo.service.venue;

public record CampusVenueCandidate(
        String id,
        String name,
        String address,
        String location,
        Integer distanceMeters,
        String type,
        String mapUrl,
        boolean schoolAffiliationNeedsVerification) {
}
