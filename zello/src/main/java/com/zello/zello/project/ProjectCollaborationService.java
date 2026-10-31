package com.zello.zello.project;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.AuthProperties;
import com.zello.zello.domain.activity.Activity;
import com.zello.zello.domain.activity.ActivityRepository;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.github.GitHubAccountRepository;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.user.User;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.GitHubInvitationAccounts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Project-row locks serialize every invitation and membership change, including acceptance. */
@Service
@Transactional(readOnly = true)
public class ProjectCollaborationService {
    private static final int MAX_PENDING_INVITATIONS = 20;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectInvitationRepository invitations;
    private final UserRepository users;
    private final GitHubAccountRepository accounts;
    private final ActivityRepository activities;
    private final ProjectService projectService;
    private final AuthProperties auth;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final SecureRandom random = new SecureRandom();

    public ProjectCollaborationService(ProjectRepository projects, ProjectMemberRepository members,
            ProjectInvitationRepository invitations, UserRepository users, GitHubAccountRepository accounts,
            ActivityRepository activities, ProjectService projectService, AuthProperties auth, Clock clock) {
        this.projects = projects;
        this.members = members;
        this.invitations = invitations;
        this.users = users;
        this.accounts = accounts;
        this.activities = activities;
        this.projectService = projectService;
        this.auth = auth;
        this.clock = clock;
    }

    public MembersPage members(long projectId, long actorId, String query, int page, int size) {
        var project = projectService.requireMember(projectId, actorId, false);
        var actor = active(projectId, actorId);
        var result = members.search(projectId, query.strip(), PageRequest.of(page - 1, size));
        boolean owner = owner(project, actorId);
        return new MembersPage(result.map(member -> memberView(project, actor, member)).getContent(),
                result.getTotalElements(), page, result.hasNext(), project.getName(), project.getVersion(),
                Long.toString(project.getOwnerUserId()), actor.getVersion(), owner || admin(actor), owner);
    }

    @Transactional
    public ProjectService.ProjectView updateProject(long projectId, long actorId, long version, String name, String description) {
        var project = lock(projectId);
        projectService.requireMember(projectId, actorId, true);
        if (!Objects.equals(project.getVersion(), version)) throw conflict();
        project.rename(name.strip());
        project.describe(description == null ? null : description.strip());
        projects.flush();
        audit(project, user(actorId), "PROJECT_DETAILS_CHANGED", "프로젝트 정보를 변경했어요", project.getName());
        return projectService.detail(projectId, actorId);
    }

    public HistoryPage history(long projectId, long actorId, int page, int size) {
        projectService.requireMember(projectId, actorId, true);
        var result = activities.membershipHistory(projectId, PageRequest.of(page - 1, size));
        return new HistoryPage(result.map(a -> new HistoryItem(a.getId().toString(), a.getEventType(), a.getTitle(),
                a.getSummary(), a.getActorAccount() == null ? null : a.getActorAccount().getLogin(), a.getOccurredAt())).getContent(),
                page, result.hasNext());
    }

    public void authorizeInvite(long projectId, long actorId, ProjectInvitation.GrantedRole role) {
        var project = projectService.requireMember(projectId, actorId, true);
        requireGrantAuthority(project, actorId, role);
    }

    @Transactional
    public CreatedInvitation createInvitation(long projectId, long actorId, ProjectInvitation.GrantedRole role,
            int expiresInHours, GitHubInvitationAccounts.Account target) {
        var project = lock(projectId);
        projectService.requireMember(projectId, actorId, true);
        requireGrantAuthority(project, actorId, role);
        var now = now();
        if (invitations.pending(projectId, now).size() >= MAX_PENDING_INVITATIONS) {
            throw new ApiException(429, "INVITATION_LIMIT_REACHED", "사용하지 않은 초대 링크가 20개 있어요. 기존 초대를 취소한 뒤 만들어 주세요.");
        }
        var actor = user(actorId);
        GitHubAccount account = target == null ? null : targetAccount(target);
        if (account != null && members.active(projectId).stream()
                .anyMatch(member -> member.getUser().getGithubAccountId().equals(account.getId()))) {
            throw new ApiException(409, "ALREADY_PROJECT_MEMBER", "이미 프로젝트에 참여한 멤버입니다.");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var invitation = invitations.saveAndFlush(ProjectInvitation.builder().project(project).invitedByUser(actor)
                .targetAccount(account).tokenHash(hash(token)).grantedRole(role).expiresAt(now.plusHours(expiresInHours)).build());
        audit(project, actor, "PROJECT_INVITATION_CREATED", "초대 링크를 만들었어요",
                "invitation=" + invitation.getId() + "; role=" + role + "; target=" + (account == null ? "link holder" : account.getGithubId()));
        return new CreatedInvitation(invitationView(invitation, now), auth.frontendBaseUrl() + "/#/invite/" + token);
    }

    public InvitationPage invitations(long projectId, long actorId, int page, int size) {
        projectService.requireMember(projectId, actorId, true);
        var result = invitations.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, PageRequest.of(page - 1, size));
        var now = now();
        return new InvitationPage(result.map(invitation -> invitationView(invitation, now)).getContent(),
                page, result.hasNext(), result.getTotalElements());
    }

    @Transactional
    public void revokeInvitation(long projectId, long actorId, long invitationId) {
        var project = lock(projectId);
        projectService.requireMember(projectId, actorId, true);
        var invitation = invitations.findByIdAndProjectId(invitationId, projectId).orElseThrow(this::invitationNotFound);
        if (!owner(project, actorId) && invitation.getGrantedRole() == ProjectInvitation.GrantedRole.ADMIN) throw ownerRequired();
        if (invitation.getAcceptedAt() != null) {
            throw new ApiException(409, "INVITATION_ALREADY_ACCEPTED", "이미 참여에 사용한 초대예요. 멤버 목록에서 권한을 관리해 주세요.");
        }
        if (invitation.getRevokedAt() != null) return;
        invitation.revoke(now());
        audit(project, user(actorId), "PROJECT_INVITATION_REVOKED", "초대 링크를 취소했어요", "invitation=" + invitationId);
    }

    public InvitationPreview preview(long actorId, String token) {
        var actor = user(actorId);
        var invitation = invitations.findByTokenHash(hash(token)).orElseThrow(this::invitationNotFound);
        var project = projects.findById(invitation.getProjectId()).filter(p -> p.getArchivedAt() == null)
                .orElseThrow(this::invitationNotFound);
        requireTarget(invitation, actor);
        var membership = members.findByIdAndLeftAtIsNull(new ProjectMemberId(project.getId(), actorId));
        var state = state(invitation, now());
        boolean issuerValid = issuerValid(project, invitation);
        if ("PENDING".equals(state) && !issuerValid) state = "REVOKED";
        return new InvitationPreview(project.getId().toString(), project.getName(), invitation.getGrantedRole().name(),
                invitation.getInvitedByUser().getGithubAccount().getLogin(), invitation.getExpiresAt(), state,
                membership.isPresent(), !membership.isPresent() && "PENDING".equals(state));
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Acceptance accept(long actorId, String token) {
        // Resolve only the project ID before taking the shared project lock; reload the invitation after locking.
        var reference = invitations.findByTokenHash(hash(token)).orElseThrow(this::invitationNotFound);
        var project = lock(reference.getProjectId());
        entityManager.refresh(reference, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var invitation = reference;
        var actor = user(actorId);
        requireTarget(invitation, actor);
        var existing = members.findById(new ProjectMemberId(project.getId(), actorId));
        boolean alreadyMember = existing.isPresent() && existing.get().getLeftAt() == null;
        if (invitation.getAcceptedAt() != null) {
            if (Objects.equals(invitation.getAcceptedByUserId(), actorId) && alreadyMember) {
                return new Acceptance(projectService.detail(project.getId(), actorId), true);
            }
            throw unavailable();
        }
        if (!"PENDING".equals(state(invitation, now())) || !issuerValid(project, invitation)) throw unavailable();
        // Existing members keep their current role. A shared link cannot promote or demote a member.
        if (alreadyMember) return new Acceptance(projectService.detail(project.getId(), actorId), true);
        var role = ProjectMember.Role.valueOf(invitation.getGrantedRole().name());
        if (existing.isPresent()) {
            existing.get().rejoin(role, now());
        } else {
            members.save(ProjectMember.builder().project(project).user(actor).role(role).build());
        }
        invitation.accept(actor, now());
        members.flush();
        audit(project, actor, "PROJECT_INVITATION_ACCEPTED", "프로젝트에 참여했어요",
                "invitation=" + invitation.getId() + "; member=" + actorId + "; role=" + role);
        return new Acceptance(projectService.detail(project.getId(), actorId), false);
    }

    @Transactional
    public MemberView changeRole(long projectId, long actorId, long targetId, long version, ProjectMember.Role role) {
        var project = lock(projectId);
        projectService.requireMember(projectId, actorId, true);
        var actor = active(projectId, actorId);
        var target = active(projectId, targetId);
        if (owner(project, targetId)) throw ownerTransferRequired();
        if (actorId == targetId) throw new ApiException(403, "SELF_ROLE_CHANGE_FORBIDDEN", "내 권한은 직접 변경할 수 없어요.");
        if (!owner(project, actorId) && (admin(target) || role == ProjectMember.Role.ADMIN)) throw ownerRequired();
        version(target, version);
        if (target.getRole() == role) return memberView(project, actor, target);
        var previous = target.getRole();
        target.changeRole(role);
        if (role != ProjectMember.Role.ADMIN) revokeIssued(projectId, targetId);
        members.flush();
        audit(project, user(actorId), "PROJECT_MEMBER_ROLE_CHANGED", "멤버 권한을 변경했어요",
                "member=" + targetId + "; role=" + previous + " -> " + role);
        return memberView(project, actor, target);
    }

    @Transactional
    public void remove(long projectId, long actorId, long targetId, long version) {
        var project = lock(projectId);
        projectService.requireMember(projectId, actorId, true);
        var target = active(projectId, targetId);
        if (owner(project, targetId)) throw ownerTransferRequired();
        if (actorId == targetId) throw new ApiException(400, "USE_PROJECT_LEAVE", "내보내기 대신 프로젝트 나가기를 이용해 주세요.");
        if (!owner(project, actorId) && admin(target)) throw ownerRequired();
        version(target, version);
        target.leave(now());
        revokeIssued(projectId, targetId);
        audit(project, user(actorId), "PROJECT_MEMBER_REMOVED", "멤버를 내보냈어요", "member=" + targetId);
    }

    @Transactional
    public void leave(long projectId, long actorId, long version) {
        var project = lock(projectId);
        var actor = active(projectId, actorId);
        if (owner(project, actorId)) throw ownerTransferRequired();
        version(actor, version);
        actor.leave(now());
        revokeIssued(projectId, actorId);
        audit(project, user(actorId), "PROJECT_MEMBER_LEFT", "프로젝트를 나갔어요", "member=" + actorId);
    }

    @Transactional
    public void transfer(long projectId, long actorId, long targetId, long projectVersion, long memberVersion) {
        var project = lock(projectId);
        projectService.requireMember(projectId, actorId, false);
        if (!owner(project, actorId)) throw ownerRequired();
        if (actorId == targetId) throw new ApiException(400, "SAME_PROJECT_OWNER", "다른 멤버에게 소유권을 이전해 주세요.");
        if (!Objects.equals(project.getVersion(), projectVersion)) throw conflict();
        var target = active(projectId, targetId);
        version(target, memberVersion);
        var previous = active(projectId, actorId);
        target.changeRole(ProjectMember.Role.ADMIN);
        previous.changeRole(ProjectMember.Role.ADMIN);
        project.transferOwnership(user(targetId));
        revokeIssued(projectId, actorId);
        members.flush();
        projects.flush();
        audit(project, user(actorId), "PROJECT_OWNERSHIP_TRANSFERRED", "프로젝트 소유권을 이전했어요",
                "owner=" + actorId + " -> " + targetId);
    }

    private void revokeIssued(long projectId, long actorId) {
        var now = now();
        invitations.pending(projectId, now).stream().filter(i -> i.getInvitedByUserId() == actorId).forEach(i -> i.revoke(now));
    }

    private boolean issuerValid(Project project, ProjectInvitation invitation) {
        if (!users.existsByIdAndStatus(invitation.getInvitedByUserId(), User.Status.ACTIVE)) return false;
        var issuer = members.findByIdAndLeftAtIsNull(new ProjectMemberId(project.getId(), invitation.getInvitedByUserId()));
        if (issuer.isEmpty()) return false;
        if (owner(project, invitation.getInvitedByUserId())) return true;
        return admin(issuer.get()) && invitation.getGrantedRole() != ProjectInvitation.GrantedRole.ADMIN;
    }

    private void requireGrantAuthority(Project project, long actorId, ProjectInvitation.GrantedRole role) {
        if (role == ProjectInvitation.GrantedRole.ADMIN && !owner(project, actorId)) throw ownerRequired();
    }

    private void requireTarget(ProjectInvitation invitation, User actor) {
        if (invitation.getTargetAccountId() != null && !invitation.getTargetAccountId().equals(actor.getGithubAccountId())) {
            throw new ApiException(403, "INVITATION_ACCOUNT_MISMATCH", "다른 GitHub 계정에 발급된 초대예요. 초대받은 계정으로 로그인해 주세요.");
        }
    }

    private GitHubAccount targetAccount(GitHubInvitationAccounts.Account remote) {
        var account = accounts.findByGithubId(remote.id()).orElseGet(() -> accounts.saveAndFlush(GitHubAccount.builder()
                .githubId(remote.id()).login(remote.login()).avatarUrl(remote.avatarUrl()).accountType(GitHubAccount.AccountType.USER).build()));
        if (account.getAccountType() != GitHubAccount.AccountType.USER) throw new ApiException(409, "GITHUB_ACCOUNT_CONFLICT", "GitHub 계정 정보를 다시 확인해 주세요.");
        account.updateProfile(remote.login(), account.getDisplayName(), remote.avatarUrl());
        return account;
    }

    private MemberView memberView(Project project, ProjectMember actor, ProjectMember target) {
        var user = target.getUser();
        var account = user.getGithubAccount();
        boolean self = actor.getId().getUserId().equals(target.getId().getUserId());
        boolean targetOwner = owner(project, target.getId().getUserId());
        boolean actorOwner = owner(project, actor.getId().getUserId());
        boolean manage = !self && !targetOwner && (actorOwner || admin(actor) && !admin(target));
        return new MemberView(user.getId().toString(), account.getGithubId().toString(), account.getLogin(),
                account.getDisplayName() == null ? account.getLogin() : account.getDisplayName(), account.getAvatarUrl(),
                targetOwner ? "OWNER" : target.getRole().name(), target.getVersion(), target.getJoinedAt(), self,
                manage, manage, actorOwner && !self);
    }

    private InvitationView invitationView(ProjectInvitation i, LocalDateTime now) {
        return new InvitationView(i.getId().toString(), i.getGrantedRole().name(), i.getExpiresAt(), i.getCreatedAt(), state(i, now),
                i.getInvitedByUser().getGithubAccount().getLogin(), i.getTargetAccount() == null ? null : i.getTargetAccount().getLogin(),
                i.getAcceptedByUserId() == null ? null : i.getAcceptedByUserId().toString());
    }

    private String state(ProjectInvitation invitation, LocalDateTime now) {
        if (invitation.getAcceptedAt() != null) return "ACCEPTED";
        if (invitation.getRevokedAt() != null) return "REVOKED";
        return now.isBefore(invitation.getExpiresAt()) ? "PENDING" : "EXPIRED";
    }

    private Project lock(long id) {
        return projects.findLockedById(id).filter(p -> p.getArchivedAt() == null).orElseThrow(ProjectService::notFound);
    }

    private ProjectMember active(long projectId, long userId) {
        user(userId);
        return members.findByIdAndLeftAtIsNull(new ProjectMemberId(projectId, userId)).orElseThrow(ProjectService::notFound);
    }

    private User user(long id) {
        return users.findWithGithubAccountById(id).filter(u -> u.getStatus() == User.Status.ACTIVE)
                .orElseThrow(() -> new ApiException(401, "ACCOUNT_INACTIVE", "사용할 수 없는 계정입니다."));
    }

    private void version(ProjectMember member, long version) {
        if (!Objects.equals(member.getVersion(), version)) throw conflict();
    }

    private void audit(Project project, User actor, String type, String title, String summary) {
        activities.save(Activity.builder().project(project).actorAccount(actor.getGithubAccount())
                .eventKey("membership:" + UUID.randomUUID()).eventType(type).title(title).summary(summary).occurredAt(now()).build());
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private boolean owner(Project project, long id) { return project.getOwnerUserId() == id; }
    private boolean admin(ProjectMember member) { return member.getRole() == ProjectMember.Role.ADMIN; }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ApiException invitationNotFound() { return new ApiException(404, "INVITATION_NOT_FOUND", "초대를 확인할 수 없어요. 링크를 다시 확인해 주세요."); }
    private ApiException unavailable() { return new ApiException(410, "INVITATION_UNAVAILABLE", "만료되거나 취소됐거나 이미 사용한 초대예요. 새 초대를 요청해 주세요."); }
    private ApiException ownerRequired() { return new ApiException(403, "PROJECT_OWNER_REQUIRED", "프로젝트 소유자만 이 권한을 관리할 수 있어요."); }
    private ApiException ownerTransferRequired() { return new ApiException(409, "OWNER_TRANSFER_REQUIRED", "소유권을 다른 멤버에게 이전한 뒤 진행해 주세요."); }
    private ApiException conflict() { return new ApiException(409, "MEMBERSHIP_CHANGED", "멤버 정보가 변경됐어요. 목록을 새로 확인한 뒤 다시 시도해 주세요."); }

    public record HistoryItem(String id, String type, String title, String summary, String actorLogin, LocalDateTime occurredAt) { }
    public record HistoryPage(List<HistoryItem> items, int page, boolean hasNext) { }
    public record MemberView(String userId, String githubId, String login, String displayName, String avatarUrl,
            String role, Long version, LocalDateTime joinedAt, boolean self, boolean canChangeRole, boolean canRemove, boolean canTransferTo) { }
    public record MembersPage(List<MemberView> items, long total, int page, boolean hasNext, String projectName, Long projectVersion,
            String ownerUserId, Long currentMemberVersion, boolean canInvite, boolean canInviteAdmin) { }
    public record InvitationView(String id, String role, LocalDateTime expiresAt, LocalDateTime createdAt, String status,
            String createdBy, String targetLogin, String acceptedByUserId) { }
    public record InvitationPage(List<InvitationView> items, int page, boolean hasNext, long total) { }
    public record CreatedInvitation(InvitationView invitation, String inviteUrl) { }
    public record InvitationPreview(String projectId, String projectName, String role, String invitedBy,
            LocalDateTime expiresAt, String status, boolean alreadyMember, boolean canAccept) { }
    public record Acceptance(ProjectService.ProjectView project, boolean alreadyMember) { }
}
