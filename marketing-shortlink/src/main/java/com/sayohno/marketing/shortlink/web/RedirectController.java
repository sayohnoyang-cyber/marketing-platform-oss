package com.sayohno.marketing.shortlink.web;

import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.service.QueryMerge;
import com.sayohno.marketing.shortlink.service.RedirectService;
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
public class RedirectController {
    private final RedirectService redirectService;
    private final MarketingProperties properties;

    public RedirectController(RedirectService redirectService, MarketingProperties properties) {
        this.redirectService = redirectService;
        this.properties = properties;
    }

    @GetMapping("/s/{shortUri}")
    public ResponseEntity<Void> redirect(@PathVariable String shortUri, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse httpResponse) {
        String visitor = com.sayohno.marketing.common.web.VisitorIdentity.resolve(request, httpResponse);
        Link link = redirectService.resolve(shortUri, visitor,
                com.sayohno.marketing.common.web.VisitorIdentity.humanCandidate(request),
                request.getParameter("c"));
        String destination = localActivityDestination(link.getOriginUrl(), properties.getShortlink().getPublicBaseUrl());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(QueryMerge.merge(destination, request.getQueryString())))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        return response.build();
    }

    static String localActivityDestination(String origin, String publicBase) {
        URI target = URI.create(origin);
        URI configured = URI.create(publicBase);
        // Keep local activity redirects on the address used by the visitor's device.
        // External destinations and production-domain redirects retain their original URL.
        if (java.util.Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(configured.getHost())
                && java.util.Objects.equals(target.getScheme(), configured.getScheme())
                && java.util.Objects.equals(target.getAuthority(), configured.getAuthority())
                && target.getPath().matches("/a/\\d+")) {
            return target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
        }
        return origin;
    }

}
