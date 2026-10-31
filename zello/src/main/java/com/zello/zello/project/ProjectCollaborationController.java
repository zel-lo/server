package com.zello.zello.project;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.domain.project.ProjectInvitation;
import com.zello.zello.domain.project.ProjectMember;
import com.zello.zello.integration.github.GitHubInvitationAccounts;
import com.zello.zello.integration.github.GitHubTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectCollaborationController {
    private final ProjectCollaborationService service;
    private final GitHubInvitationAccounts accounts;
    private final GitHubTokenProvider tokens;

    public ProjectCollaborationController(ProjectCollaborationService service, GitHubInvitationAccounts accounts, GitHubTokenProvider tokens) {
        this.service = service;
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PatchMapping("/projects/{projectId}")
    ResponseEntity<ProjectService.ProjectView> updateProject(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal actor, @Valid @RequestBody UpdateProject input) {
        return ok(service.updateProject(projectId, actor.userId(), input.version(), input.name(), input.description()));
    }

    @GetMapping("/projects/{projectId}/members")
    ResponseEntity<ProjectCollaborationService.MembersPage> members(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal actor, @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        page(page, size);
        if (query.length() > 100) throw new ApiException(400, "INVALID_QUERY", "검색어는 100자 이내로 입력해 주세요.");
        return ok(service.members(projectId, actor.userId(), query, page, size));
    }

    @GetMapping("/projects/{projectId}/membership-history")
    ResponseEntity<ProjectCollaborationService.HistoryPage> history(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal actor, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        page(page, size);
        return ok(service.history(projectId, actor.userId(), page, size));
    }

    @PostMapping("/projects/{projectId}/invitations")
    ResponseEntity<ProjectCollaborationService.CreatedInvitation> create(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal actor, Authentication authentication, HttpServletRequest request,
            @Valid @RequestBody CreateInvitation input) {
        service.authorizeInvite(projectId, actor.userId(), input.role());
        var target = input.githubLogin() == null ? null : accounts.resolve(tokens.token(authentication, request), input.githubLogin());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.createInvitation(projectId, actor.userId(), input.role(), input.expiresInHours(), target));
    }

    @GetMapping("/projects/{projectId}/invitations")
    ResponseEntity<ProjectCollaborationService.InvitationPage> invitations(@PathVariable long projectId,
            @AuthenticationPrincipal ZelloPrincipal actor, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        page(page, size);
        return ok(service.invitations(projectId, actor.userId(), page, size));
    }

    @DeleteMapping("/projects/{projectId}/invitations/{invitationId}")
    ResponseEntity<Void> revoke(@PathVariable long projectId, @PathVariable long invitationId,
            @AuthenticationPrincipal ZelloPrincipal actor) {
        service.revokeInvitation(projectId, actor.userId(), invitationId);
        return ResponseEntity.noContent().build();
    }

    // Tokens stay in the POST body; invite links use the SPA fragment, never a server query string.
    @PostMapping("/invitations/preview")
    ResponseEntity<ProjectCollaborationService.InvitationPreview> preview(@AuthenticationPrincipal ZelloPrincipal actor,
            @Valid @RequestBody InvitationToken input) {
        return ok(service.preview(actor.userId(), input.token()));
    }

    @PostMapping("/invitations/accept")
    ResponseEntity<ProjectCollaborationService.Acceptance> accept(@AuthenticationPrincipal ZelloPrincipal actor,
            @Valid @RequestBody InvitationToken input) {
        return ok(service.accept(actor.userId(), input.token()));
    }

    @PatchMapping("/projects/{projectId}/members/{userId}")
    ResponseEntity<ProjectCollaborationService.MemberView> role(@PathVariable long projectId, @PathVariable long userId,
            @AuthenticationPrincipal ZelloPrincipal actor, @Valid @RequestBody ChangeRole input) {
        return ok(service.changeRole(projectId, actor.userId(), userId, input.version(), input.role()));
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    ResponseEntity<Void> remove(@PathVariable long projectId, @PathVariable long userId, @RequestParam long version,
            @AuthenticationPrincipal ZelloPrincipal actor) {
        service.remove(projectId, actor.userId(), userId, version);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/projects/{projectId}/membership")
    ResponseEntity<Void> leave(@PathVariable long projectId, @RequestParam long version,
            @AuthenticationPrincipal ZelloPrincipal actor) {
        service.leave(projectId, actor.userId(), version);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{projectId}/ownership")
    ResponseEntity<Void> ownership(@PathVariable long projectId, @AuthenticationPrincipal ZelloPrincipal actor,
            @Valid @RequestBody TransferOwnership input) {
        service.transfer(projectId, actor.userId(), input.userId(), input.projectVersion(), input.memberVersion());
        return ResponseEntity.noContent().build();
    }

    private void page(int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 100) throw new ApiException(400, "INVALID_PAGE", "페이지 범위를 확인해 주세요.");
    }

    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    public record UpdateProject(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 100) String name,
            @Size(max = 4000) String description) { }
    public record CreateInvitation(@NotNull ProjectInvitation.GrantedRole role, @Min(1) @Max(168) int expiresInHours,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{0,38}") String githubLogin) { }
    public record InvitationToken(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) { }
    public record ChangeRole(@NotNull ProjectMember.Role role, @NotNull @PositiveOrZero Long version) { }
    public record TransferOwnership(@NotNull @Positive Long userId, @NotNull @PositiveOrZero Long projectVersion,
            @NotNull @PositiveOrZero Long memberVersion) { }
}
