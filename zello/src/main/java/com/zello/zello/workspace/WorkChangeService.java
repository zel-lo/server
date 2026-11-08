package com.zello.zello.workspace;

import com.zello.zello.domain.activity.Activity;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.common.PublishStatus;
import com.zello.zello.domain.issue.*;
import com.zello.zello.domain.patchnote.*;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.domain.user.User;
import com.zello.zello.project.ProjectService;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkChangeService {
    private final EntityManager em;
    private final ProjectRepository projects;
    private final RuleBasedChangeFormatter formatter;
    private final ProjectService access;
    public WorkChangeService(EntityManager em, ProjectRepository projects, RuleBasedChangeFormatter formatter, ProjectService access) {
        this.access = access;
        this.em = em; this.projects = projects; this.formatter = formatter;
    }
    public void issue(Issue issue) {
        long project = issue.getRepository().getProjectId();
        projects.findLockedById(project).orElseThrow(ProjectService::notFound);
        String key = "issue:" + issue.getId();
        var existing = find(project, key);
        if (existing == null && (issue.getWorkStatus() != Issue.WorkStatus.DONE || issue.getArchivedAt() != null)) return;
        var merged = em.createQuery("select l.pullRequest from IssuePullRequest l where l.id.issueId=:id and l.pullRequest.state=:state and l.pullRequest.publishStatus=:published order by l.pullRequest.id", PullRequest.class)
                .setParameter("id", issue.getId()).setParameter("state", PullRequest.State.MERGED).setParameter("published", PublishStatus.PUBLISHED).getResultList();
        String status = issue.getArchivedAt() != null ? "ARCHIVED" : issue.getWorkStatus() != Issue.WorkStatus.DONE ? "REOPENED" : merged.isEmpty() ? "COMPLETED" : "MERGED";
        if (existing == null) existing = create(project, issue.getRepositoryId(), key, issue.getId(), null, issue.getCompletedAt());
        String summary = formatter.summary(issue.getDescription());
        if (!merged.isEmpty()) summary += "\n연결된 병합 PR: " + String.join(", ", merged.stream().map(p -> "#" + p.getGithubNumber()).toList());
        update(existing, issue.getTitle(), summary, formatter.category(issue.getTitle()), status);
        // A previously standalone PR becomes supporting evidence for the linked work, rather than a second item.
        for (var pull : merged) {
            var standalone = find(project, "pr:" + pull.getId());
            if (standalone != null) {
                if (!existing.isTitleWritten() && standalone.isTitleWritten()
                        && em.find(PatchNote.class, existing.getPatchNoteId()).getStatus() == PatchNote.Status.DRAFT) {
                    writeTitle(existing, standalone.getTitle());
                }
                update(standalone, standalone.getTitle(), "연결된 작업 " + issue.getIssueKey() + "의 변경 기록으로 통합됐습니다.", standalone.getCategory(), "CONSOLIDATED");
            }
        }
    }
    public void issueTitle(Issue issue, String title) {
        if (title == null) return;
        if (issue.getWorkStatus() != Issue.WorkStatus.DONE)
            throw new com.zello.zello.api.ApiException(409, "WORK_NOT_COMPLETED", "완료된 작업에 패치노트 제목을 작성할 수 있어요.");
        issue(issue);
        var entry = find(issue.getRepository().getProjectId(), "issue:" + issue.getId());
        if (em.find(PatchNote.class, entry.getPatchNoteId()).getStatus() == PatchNote.Status.PUBLISHED) return;
        writeTitle(entry, title);
    }
    public void writeTitle(WorkChange entry, String title) {
        var note = em.find(PatchNote.class, entry.getPatchNoteId());
        if (note.getStatus() == PatchNote.Status.PUBLISHED)
            throw new com.zello.zello.api.ApiException(409, "NOTE_PUBLISHED", "공개한 패치노트의 반영 사항은 변경할 수 없어요.");
        entry.writeTitle(title);
        em.lock(note, jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
    public void pull(PullRequest pull) {
        long project = pull.getRepository().getProjectId();
        projects.findLockedById(project).orElseThrow(ProjectService::notFound);
        var linked = em.createQuery("select l.issue from IssuePullRequest l where l.id.pullRequestId=:id", Issue.class).setParameter("id", pull.getId()).getResultList();
        if (!linked.isEmpty()) {
            for (var issue : linked) issue(issue);
            var standalone = find(project, "pr:" + pull.getId());
            if (standalone != null) update(standalone, standalone.getTitle(), "연결된 작업의 변경 기록으로 통합됐습니다.", standalone.getCategory(), "CONSOLIDATED");
            return;
        }
        if (pull.getState() != PullRequest.State.MERGED || pull.getPublishStatus() != PublishStatus.PUBLISHED) return;
        var entry = find(project, "pr:" + pull.getId());
        if (entry == null) entry = create(project, pull.getRepositoryId(), "pr:" + pull.getId(), null, pull.getId(), pull.getMergedAt());
        String description = pull.getTeamSummary() == null || pull.getTeamSummary().isBlank() ? pull.getBody() : pull.getTeamSummary();
        update(entry, pull.getTitle(), formatter.summary(description), formatter.category(pull.getTitle()), "MERGED");
    }
    private void update(WorkChange entry, String title, String summary, String category, String status) {
        var note = em.find(PatchNote.class, entry.getPatchNoteId());
        if (entry.isTitleWritten()) title = entry.getTitle();
        // Published prose is a snapshot. Source status may still change after reopening or consolidation.
        if (note.getStatus() == PatchNote.Status.PUBLISHED) { title = entry.getTitle(); summary = entry.getSummary(); category = entry.getCategory(); }
        if (Objects.equals(entry.getTitle(), title) && Objects.equals(entry.getSummary(), summary)
                && Objects.equals(entry.getCategory(), category) && Objects.equals(entry.getStatus(), status)) return;
        entry.update(title, summary, category, status);
        em.lock(note, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }
    public void collect(long projectId, long userId) {
        projects.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        access.requireWriter(projectId, userId);
        var issues = em.createQuery("select i from Issue i where i.repository.projectId=:p and i.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED", Issue.class).setParameter("p", projectId).getResultList();
        for (var issue : issues) issue(issue);
        var pulls = em.createQuery("select p from PullRequest p where p.repository.projectId=:id and p.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED and p.state=:state", PullRequest.class).setParameter("id", projectId).setParameter("state", PullRequest.State.MERGED).getResultList();
        for (var pull : pulls) pull(pull);
    }
    private WorkChange find(long project, String key) {
        return em.createQuery("select c from WorkChange c where c.projectId=:p and c.sourceKey=:key", WorkChange.class)
                .setParameter("p", project).setParameter("key", key).getResultStream().findFirst().orElse(null);
    }
    private WorkChange create(long projectId, long repository, String key, Long issue, Long pull, LocalDateTime occurred) {
        var project = projects.findById(projectId).orElseThrow(ProjectService::notFound);
        var now = EntitySupport.now();
        // Group newly observed completions by the current Korean calendar week; imported old work stays visible in the current draft.
        var monday = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate().with(DayOfWeek.MONDAY);
        String marker = "auto-work:" + monday;
        var drafts = em.createQuery("select n from PatchNote n where n.projectId=:p and n.versionLabel=:marker and n.status=:status order by n.id desc", PatchNote.class)
                .setParameter("p", projectId).setParameter("marker", marker).setParameter("status", PatchNote.Status.DRAFT).setMaxResults(1).getResultList();
        PatchNote note;
        if (drafts.isEmpty()) {
            note = PatchNote.builder().project(project).authorUser(em.find(User.class, project.getOwnerUserId()))
                    .title(monday + " 주간 변경 사항").summary("완료한 작업과 병합된 PR을 모았습니다. 내용을 확인한 뒤 팀에 공개하세요.")
                    .versionLabel(marker).build();
            em.persist(note); em.flush();
        } else note = drafts.getFirst();
        var entry = new WorkChange(projectId, repository, key, issue, pull, note.getId(), occurred == null ? now : occurred);
        // Initialize non-null fields before IDENTITY insertion; callers fill the source wording next.
        entry.update("작업 변경", "", "IMPROVE", "COMPLETED");
        em.persist(entry); em.flush();
        var repo = em.find(com.zello.zello.domain.repository.GitRepository.class, repository);
        var event = Activity.builder().project(project).repository(repo).issue(issue == null ? null : em.find(Issue.class, issue))
                .pullRequest(pull == null ? null : em.find(PullRequest.class, pull)).eventKey("work-change:" + key)
                .eventType("WORK_COMPLETED").title(issue == null ? em.find(PullRequest.class, pull).getTitle() : em.find(Issue.class, issue).getTitle())
                .summary("완료 기록이 패치노트 초안에 추가됐습니다.").occurredAt(entry.getOccurredAt()).build();
        em.persist(event);
        return entry;
    }
}
