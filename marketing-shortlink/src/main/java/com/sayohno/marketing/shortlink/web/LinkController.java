package com.sayohno.marketing.shortlink.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.web.dto.CreateGroupRequest;
import com.sayohno.marketing.shortlink.web.dto.CreateLinkRequest;
import com.sayohno.marketing.shortlink.web.dto.UpdateLinkRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant")
public class LinkController {
    private final LinkDirectoryService directoryService;

    public LinkController(LinkDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @PostMapping("/link-groups")
    public Result<?> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        return Results.success(directoryService.createGroup(request.getName()));
    }

    @GetMapping("/link-groups")
    public Result<?> groups() {
        return Results.success(directoryService.listGroups());
    }

    @PostMapping("/links")
    public Result<?> createLink(@Valid @RequestBody CreateLinkRequest request, HttpServletRequest http) {
        return Results.success(directoryService.createLink(request.getGid(), request.getOriginUrl(), request.getValidDate(),
                request.getIdempotencyKey(), http.getHeader("X-Forwarded-Host"), http.getHeader("X-Forwarded-Proto"), request.getName(), request.getShortUri(), request.getActivityId()));
    }

    @GetMapping("/links")
    public Result<?> links(@RequestParam(required = false) String gid, @RequestParam(defaultValue = "false") boolean recycle) {
        return Results.success(directoryService.listLinks(gid, recycle));
    }

    @PutMapping("/links/{id}")
    public Result<?> update(@PathVariable long id, @RequestBody UpdateLinkRequest request) {
        return Results.success(directoryService.updateLink(id, request.getOriginUrl(), request.getValidDate(), request.getEnableStatus()));
    }

    @PostMapping("/links/{id}/recycle")
    public Result<?> recycle(@PathVariable long id) {
        directoryService.recycle(id);
        return Results.success();
    }

    @PostMapping("/links/{id}/restore")
    public Result<?> restore(@PathVariable long id) {
        return Results.success(directoryService.restore(id));
    }
}
