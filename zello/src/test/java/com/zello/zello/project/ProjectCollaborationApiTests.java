package com.zello.zello.project;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.api.ApiException;
import com.zello.zello.auth.*;
import com.zello.zello.domain.activity.ActivityRepository;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "zello.auth.github.app-type=OAUTH")
@AutoConfigureMockMvc
class ProjectCollaborationApiTests {
    static final AtomicLong IDS = new AtomicLong(710_000_000);
    @Autowired MockMvc mvc;
    @Autowired GitHubLoginService login;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired ProjectInvitationRepository invitations;
    @Autowired UserRepository users;
    @Autowired ActivityRepository activities;
    @Autowired ProjectCollaborationService collaboration;
    @MockitoBean Clock clock;
    @MockitoBean GitHubInvitationAccounts accounts;
    @MockitoBean GitHubTokenProvider tokens;
    final AtomicReference<Instant> time = new AtomicReference<>();
    ZelloPrincipal owner, admin, member, viewer;
    long projectId;

    @BeforeEach void setup() throws Exception {
        time.set(Instant.now().plusSeconds(300));
        when(clock.instant()).thenAnswer(call -> time.get());
        when(tokens.token(any(), any())).thenReturn("test-authorized-token");
        owner = user("owner");
        projectId = Long.parseLong(JsonPath.read(mvc.perform(post("/api/projects").with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"name\":\"협업 프로젝트\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
        admin = member(ProjectMember.Role.ADMIN);
        member = member(ProjectMember.Role.MEMBER);
        viewer = member(ProjectMember.Role.VIEWER);
    }

    @Test void listsRealMembersWithRolesPermissionsAndPagination() throws Exception {
        mvc.perform(get(base("/members")).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.canInviteAdmin").value(true))
                .andExpect(jsonPath("$.items[0].role").value("OWNER")).andExpect(jsonPath("$.items[0].self").value(true))
                .andExpect(jsonPath("$.items[0].canRemove").value(false));
        mvc.perform(get(base("/members") + "?size=2").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.canInvite").value(false));
        mvc.perform(get(base("/members") + "?query=" + account(member)).with(as(owner)))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(base("/members") + "?size=1000").with(as(owner))).andExpect(status().isBadRequest());
    }

    @Test void createsHashedSingleUseLinksAndNeverReturnsSecretsInListsOrHistory() throws Exception {
        var created = create(owner, "MEMBER", 72, null);
        assertThat(created.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        var row = invitations.findById(created.id()).orElseThrow();
        assertThat(row.getTokenHash()).hasSize(64).isNotEqualTo(created.token());
        var outsider = user("new-member");
        preview(outsider, created.token()).andExpect(status().isOk()).andExpect(jsonPath("$.canAccept").value(true));
        accept(outsider, created.token()).andExpect(status().isOk()).andExpect(jsonPath("$.project.role").value("MEMBER"));
        accept(outsider, created.token()).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyMember").value(true));
        accept(user("second"), created.token()).andExpect(status().isGone());
        String listing = mvc.perform(get(base("/invitations")).with(as(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String history = mvc.perform(get(base("/membership-history")).with(as(owner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listing + history).doesNotContain(created.token(), row.getTokenHash(), "tokenHash", "inviteUrl");
        assertThat(members.active(projectId)).hasSize(5);
        assertThat(activities.membershipHistory(projectId, org.springframework.data.domain.PageRequest.of(0, 100)).getContent()
                .stream().filter(a -> a.getEventType().equals("PROJECT_INVITATION_ACCEPTED"))).hasSize(1);
    }

    @Test void previewRequiresAuthenticationAndNeverAcceptsOnVisit() throws Exception {
        var created = create(owner, "MEMBER", 72, null);
        mvc.perform(post("/api/invitations/preview").with(csrf()).contentType("application/json").content(body(created.token())))
                .andExpect(status().isUnauthorized());
        preview(user("reader"), created.token()).andExpect(status().isOk());
        assertThat(invitations.findById(created.id()).orElseThrow().getAcceptedAt()).isNull();
        assertThat(members.active(projectId)).hasSize(4);
        mvc.perform(post("/api/invitations/accept").with(as(member)).contentType("application/json").content(body(created.token())))
                .andExpect(status().isForbidden());
    }

    @Test void exactExpiryBoundaryIsRejectedAndRevocationIsIdempotent() throws Exception {
        var expired = create(owner, "MEMBER", 1, null);
        // Compare against the persisted boundary: the DB may round nanoseconds.
        var expiresAt = invitations.findById(expired.id()).orElseThrow().getExpiresAt();
        time.set(expiresAt.minusNanos(1).toInstant(ZoneOffset.UTC));
        preview(user("before-expiry"), expired.token()).andExpect(jsonPath("$.status").value("PENDING"));
        time.set(expiresAt.toInstant(ZoneOffset.UTC));
        preview(user("expired"), expired.token()).andExpect(jsonPath("$.status").value("EXPIRED"));
        accept(user("expired-accept"), expired.token()).andExpect(status().isGone());
        var revoked = create(owner, "VIEWER", 24, null);
        mvc.perform(delete(base("/invitations/" + revoked.id())).with(as(admin)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(base("/invitations/" + revoked.id())).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        accept(user("revoked"), revoked.token()).andExpect(status().isGone());
    }

    @Test void targetedInvitesBindToGithubIdAcrossUsernameChangesIncludingNewAccounts() throws Exception {
        long durableId = IDS.incrementAndGet();
        when(accounts.resolve(anyString(), eq("original-login"))).thenReturn(new GitHubInvitationAccounts.Account(durableId, "original-login", "User", null));
        var created = create(owner, "MEMBER", 72, "original-login");
        preview(user("wrong-account"), created.token()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_ACCOUNT_MISMATCH"));
        var renamed = login.login(new GitHubProfile(durableId, "renamed-login", null, null, null));
        accept(renamed, created.token()).andExpect(status().isOk());
        assertThat(invitations.findById(created.id()).orElseThrow().getAcceptedByUserId()).isEqualTo(renamed.userId());
    }

    @Test void adminsCannotIssueAdminInvitesChangeAdminsRemoveOwnersOrPromoteThemselves() throws Exception {
        mvc.perform(post(base("/invitations")).with(as(admin)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"ADMIN\",\"expiresInHours\":72,\"githubLogin\":\"someone\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(accounts);
        create(admin, "MEMBER", 72, null);
        change(admin, member, "ADMIN", version(member)).andExpect(status().isForbidden());
        change(admin, admin, "VIEWER", version(admin)).andExpect(status().isForbidden());
        change(admin, owner, "MEMBER", version(owner)).andExpect(status().isConflict());
        mvc.perform(delete(base("/members/" + owner.userId()) + "?version=" + version(owner)).with(as(admin)).with(csrf()))
                .andExpect(status().isConflict());
        var otherAdmin = member(ProjectMember.Role.ADMIN);
        change(admin, otherAdmin, "MEMBER", version(otherAdmin)).andExpect(status().isForbidden());
        var privileged = create(owner, "ADMIN", 72, null);
        mvc.perform(delete(base("/invitations/" + privileged.id())).with(as(admin)).with(csrf())).andExpect(status().isForbidden());
    }

    @Test void existingMembersCannotUseLinksToEscalateAndDoNotConsumeThem() throws Exception {
        var invitation = create(owner, "ADMIN", 72, null);
        accept(viewer, invitation.token()).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyMember").value(true))
                .andExpect(jsonPath("$.project.role").value("VIEWER"));
        assertThat(invitations.findById(invitation.id()).orElseThrow().getAcceptedAt()).isNull();
        accept(user("actual-admin"), invitation.token()).andExpect(jsonPath("$.project.role").value("ADMIN"));
    }

    @Test void staleRoleUpdatesFailAndOwnerCanManageAdmins() throws Exception {
        long version = version(member);
        change(owner, member, "VIEWER", version).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
        change(owner, member, "ADMIN", version).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBERSHIP_CHANGED"));
        change(owner, member, "ADMIN", version(member)).andExpect(status().isOk());
        change(owner, admin, "MEMBER", version(admin)).andExpect(status().isOk());
    }

    @Test void demotionRevokesIssuedInvitesAndMembersCannotManageMembership() throws Exception {
        var invitation = create(admin, "MEMBER", 72, null);
        change(owner, admin, "MEMBER", version(admin)).andExpect(status().isOk());
        accept(user("late"), invitation.token()).andExpect(status().isGone());
        mvc.perform(get(base("/invitations")).with(as(member))).andExpect(status().isForbidden());
        mvc.perform(get(base("/membership-history")).with(as(viewer))).andExpect(status().isForbidden());
        change(viewer, member, "VIEWER", version(member)).andExpect(status().isForbidden());
    }

    @Test void removalBlocksAccessImmediatelyAndAnOldAcceptedLinkCannotRejoin() throws Exception {
        var joiner = user("joiner");
        var invitation = create(owner, "MEMBER", 72, null);
        accept(joiner, invitation.token()).andExpect(status().isOk());
        long previous = version(joiner);
        mvc.perform(delete(base("/members/" + joiner.userId()) + "?version=" + previous).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(base("/members")).with(as(joiner))).andExpect(status().isNotFound());
        mvc.perform(get(base("")).with(as(joiner))).andExpect(status().isNotFound());
        accept(joiner, invitation.token()).andExpect(status().isGone());
        assertThat(members.findById(new ProjectMemberId(projectId, joiner.userId())).orElseThrow().getLeftAt()).isNotNull();
        accept(joiner, create(owner, "VIEWER", 72, null).token()).andExpect(jsonPath("$.project.role").value("VIEWER"));
        mvc.perform(delete(base("/members/" + joiner.userId()) + "?version=" + previous).with(as(owner)).with(csrf())).andExpect(status().isConflict());
    }

    @Test void ownerMustTransferBeforeLeavingAndTransferIsAtomicAndVersioned() throws Exception {
        mvc.perform(delete(base("/membership") + "?version=" + version(owner)).with(as(owner)).with(csrf())).andExpect(status().isConflict());
        var invitation = create(owner, "ADMIN", 72, null);
        long version = projects.findById(projectId).orElseThrow().getVersion();
        transfer(admin, member, version, version(member)).andExpect(status().isForbidden());
        transfer(owner, member, version + 1, version(member)).andExpect(status().isConflict());
        transfer(owner, member, version, version(member)).andExpect(status().isNoContent());
        assertThat(projects.findById(projectId).orElseThrow().getOwnerUserId()).isEqualTo(member.userId());
        assertThat(members.findById(new ProjectMemberId(projectId, member.userId())).orElseThrow().getRole()).isEqualTo(ProjectMember.Role.ADMIN);
        mvc.perform(get(base("/members")).with(as(owner))).andExpect(jsonPath("$.canInviteAdmin").value(false));
        accept(user("old-owner-invite"), invitation.token()).andExpect(status().isGone());
        mvc.perform(delete(base("/membership") + "?version=" + version(owner)).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(base("")).with(as(owner))).andExpect(status().isNotFound());
    }

    @Test void leavingAnAdminRevokesTheirUnusedInvitations() throws Exception {
        var invitation = create(admin, "MEMBER", 72, null);
        mvc.perform(delete(base("/membership") + "?version=" + version(admin)).with(as(admin)).with(csrf())).andExpect(status().isNoContent());
        accept(user("after-leave"), invitation.token()).andExpect(status().isGone());
    }

    @Test void validatesInputQuotasAndDoesNotLeakAnotherProject() throws Exception {
        var outsider = user("outsider");
        mvc.perform(get(base("/members")).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(post(base("/invitations")).with(as(member)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"MEMBER\",\"expiresInHours\":72}")) .andExpect(status().isForbidden());
        mvc.perform(post(base("/invitations")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"OWNER\",\"expiresInHours\":72}")) .andExpect(status().isBadRequest());
        mvc.perform(post(base("/invitations")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"MEMBER\",\"expiresInHours\":169}")) .andExpect(status().isBadRequest());
        var invitation = create(owner, "MEMBER", 72, null);
        mvc.perform(delete("/api/projects/999999999/invitations/" + invitation.id()).with(as(owner)).with(csrf())).andExpect(status().isNotFound());
        var tokens = new HashSet<String>(); tokens.add(invitation.token());
        for (int i = 1; i < 20; i++) tokens.add(create(owner, "MEMBER", 72, null).token());
        assertThat(tokens).hasSize(20);
        mvc.perform(post(base("/invitations")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"MEMBER\",\"expiresInHours\":72}")) .andExpect(status().isTooManyRequests());
    }

    @Test void concurrentDifferentUsersCannotConsumeTheSameInvitation() throws Exception {
        var invite = create(owner, "MEMBER", 72, null);
        var first = user("race-one"); var second = user("race-two");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrentAccept(first.userId(), invite.token(), barrier));
            var b = executor.submit(() -> concurrentAccept(second.userId(), invite.token(), barrier));
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 410);
        }
        assertThat(members.active(projectId)).hasSize(5);
    }

    @Test void concurrentSameUserAcceptanceIsIdempotent() throws Exception {
        var invite = create(owner, "MEMBER", 72, null); var joiner = user("same-race"); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> concurrentAccept(joiner.userId(), invite.token(), barrier));
            var b = executor.submit(() -> concurrentAccept(joiner.userId(), invite.token(), barrier));
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsOnly(200);
        }
        assertThat(members.active(projectId)).hasSize(5);
    }

    @Test void projectSettingsAreRealAndProtectAgainstLostUpdates() throws Exception {
        long version = projects.findById(projectId).orElseThrow().getVersion();
        String json = "{\"version\":" + version + ",\"name\":\"새 프로젝트 이름\",\"description\":\"실제 설명\"}";
        mvc.perform(patch(base("")).with(as(owner)).with(csrf()).contentType("application/json").content(json)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 프로젝트 이름"));
        mvc.perform(patch(base("")).with(as(owner)).with(csrf()).contentType("application/json").content(json)).andExpect(status().isConflict());
        mvc.perform(patch(base("")).with(as(viewer)).with(csrf()).contentType("application/json").content(json)).andExpect(status().isForbidden());
        assertThat(projects.findById(projectId).orElseThrow().getDescription()).isEqualTo("실제 설명");
    }

    int concurrentAccept(long userId, String token, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try { collaboration.accept(userId, token); return 200; } catch (ApiException e) { return e.status(); }
    }
    ZelloPrincipal user(String name) { long id = IDS.incrementAndGet(); return login.login(new GitHubProfile(id, name + "-" + id, null, null, null)); }
    ZelloPrincipal member(ProjectMember.Role role) {
        var user = user(role.name().toLowerCase());
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(user.userId()).orElseThrow()).role(role).build()); return user;
    }
    String account(ZelloPrincipal user) { return users.findWithGithubAccountById(user.userId()).orElseThrow().getGithubAccount().getLogin(); }
    long version(ZelloPrincipal user) { return members.findById(new ProjectMemberId(projectId, user.userId())).orElseThrow().getVersion(); }
    record Link(long id, String token) { }
    Link create(ZelloPrincipal actor, String role, int hours, String target) throws Exception {
        String response = mvc.perform(post(base("/invitations")).with(as(actor)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"" + role + "\",\"expiresInHours\":" + hours + ",\"githubLogin\":" + (target == null ? "null" : "\"" + target + "\"") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(response, "$.inviteUrl");
        return new Link(Long.parseLong(JsonPath.read(response, "$.invitation.id")), url.substring(url.lastIndexOf('/') + 1));
    }
    ResultActions preview(ZelloPrincipal actor, String token) throws Exception {
        return mvc.perform(post("/api/invitations/preview").with(as(actor)).with(csrf()).contentType("application/json").content(body(token)));
    }
    ResultActions accept(ZelloPrincipal actor, String token) throws Exception {
        return mvc.perform(post("/api/invitations/accept").with(as(actor)).with(csrf()).contentType("application/json").content(body(token)));
    }
    ResultActions change(ZelloPrincipal actor, ZelloPrincipal target, String role, long version) throws Exception {
        return mvc.perform(patch(base("/members/" + target.userId())).with(as(actor)).with(csrf()).contentType("application/json")
                .content("{\"role\":\"" + role + "\",\"version\":" + version + "}"));
    }
    ResultActions transfer(ZelloPrincipal actor, ZelloPrincipal target, long version, long memberVersion) throws Exception {
        return mvc.perform(post(base("/ownership")).with(as(actor)).with(csrf()).contentType("application/json")
                .content("{\"userId\":\"" + target.userId() + "\",\"projectVersion\":" + version + ",\"memberVersion\":" + memberVersion + "}"));
    }
    String body(String token) { return "{\"token\":\"" + token + "\"}"; }
    String base(String path) { return "/api/projects/" + projectId + path; }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(ZelloPrincipal actor) {
        return authentication(new UsernamePasswordAuthenticationToken(actor, "unused", actor.getAuthorities()));
    }
}
