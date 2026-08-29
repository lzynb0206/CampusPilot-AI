package com.example.demo.service.venue;

public interface CampusVenueSearchProvider {
    CampusVenueSearchResult search(
            String school,
            String city,
            CampusVenuePreference preference);

    static CampusVenueSearchProvider disabled() {
        return (school, city, preference) -> CampusVenueSearchResult.notConfigured();
    }
}
