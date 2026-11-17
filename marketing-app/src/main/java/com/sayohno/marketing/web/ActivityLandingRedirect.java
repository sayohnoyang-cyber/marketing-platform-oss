package com.sayohno.marketing.web;

import com.sayohno.marketing.common.config.MarketingProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class ActivityLandingRedirect {
    private final MarketingProperties properties;

    public ActivityLandingRedirect(MarketingProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/a/{activityId:\\d+}")
    public ResponseEntity<Void> redirect(@PathVariable long activityId, HttpServletRequest request) {
        String base = properties.getWeb().getConsoleBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String query = request.getQueryString();
        String suffix = query == null || !query.matches("[A-Za-z0-9=&%._~-]{1,200}") ? "" : "?" + query;
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(base + "/a/" + activityId + suffix))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
