package com.zello.zello.workspace;

import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.issue.Issue;
import com.zello.zello.domain.patchnote.WorkChange;
import com.zello.zello.domain.project.ProjectMember;
import com.zello.zello.domain.pullrequest.*;
import com.zello.zello.project.ProjectService;
import com.zello.zello.project.ProjectWeeks;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DashboardService {
    private final EntityManager em;
    private final ProjectService projects;
    public DashboardService(EntityManager em, ProjectService projects) { this.em = em; this.projects = projects; }

    private static Issue.WorkStatus boardStatus(Issue.WorkStatus status) {
        return status == Issue.WorkStatus.TODO ? Issue.WorkStatus.IN_PROGRESS : status;
    }

    public record Task(String id, String title, String type, String status, String repository) { }
    public record Person(String login, String name, String avatarUrl, String jobTitle, long inProgress,
                         long reviewing, long completed, long assigned, List<Task> tasks) { }
    public record Attention(String id, String kind, String title, String repository, String owner,
                            Long ageDays, String timeLabel, List<String> reviewers) { }
    public record AttentionCounts(long unassigned, long reviewAging, long changesRequested) { }
    public record Completed(String id, String type, String title, String owner, String repository,
                            LocalDateTime completedAt, boolean needsTitle) { }
    public record Dashboard(LocalDate weekStart, LocalDate weekEnd, String periodLabel, String timezone,
                            long total, long completed, int percentage, long carriedOver, long completedThisWeek,
                            Map<String, Long> states, AttentionCounts attentionCounts, List<Attention> attention,
                            List<Person> people, List<Completed> recent, long recentTotal, LocalDateTime fetchedAt,
                            LocalDate currentWeek, LocalDate projectStartDate, LocalDate projectEndDate) { }

    public Dashboard read(long projectId, long userId) {
        return read(projectId, userId, null);
    }

    public Dashboard read(long projectId, long userId, LocalDate selectedWeek) {
        var project = projects.requireMember(projectId, userId, false);
        var now = EntitySupport.now();
        var currentWeek = ProjectWeeks.current(project);
        ProjectWeeks.requireWeek(project, selectedWeek);
        var week = selectedWeek == null ? currentWeek : selectedWeek;
        var start = ProjectWeeks.begins(project, week);
        var endDate = project.getEndDate() != null && project.getEndDate().isBefore(week.plusDays(6)) ? project.getEndDate().plusDays(1) : week.plusWeeks(1);
        var end = ProjectWeeks.begins(project, endDate);
        var today = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(project.getTimezone())).toLocalDate();
        var period = !today.isBefore(week) && today.isBefore(week.plusWeeks(1)) ? "이번 주" : today.isBefore(week) ? "프로젝트 첫 주" : "프로젝트 마지막 주";
        if (!week.equals(currentWeek)) {
            period = project.getStartDate() != null ? (ChronoUnit.DAYS.between(project.getStartDate(), week) / 7 + 1) + "주차"
                    : week.equals(currentWeek.minusWeeks(1)) ? "지난 주" : week.equals(currentWeek.plusWeeks(1)) ? "다음 주" : "선택한 주";
        }
        String connected = " and i.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED";
        var issues = em.createQuery("select i from Issue i join fetch i.repository left join fetch i.assigneeAccount where i.repository.projectId=:p and i.archivedAt is null" + connected
                + " and ((i.workStatus not in (com.zello.zello.domain.issue.Issue.WorkStatus.DONE,com.zello.zello.domain.issue.Issue.WorkStatus.BACKLOG) and i.plannedWeekStart<=:week)"
                + " or (i.workStatus=com.zello.zello.domain.issue.Issue.WorkStatus.DONE and i.completedAt>=:start and i.completedAt<:end))", Issue.class)
                .setParameter("p", projectId).setParameter("start", start).setParameter("end", end).setParameter("week", week).getResultList();
        // Match the weekly board: current unfinished work and completions in the selected interval, not a historical status snapshot.
        var scope = issues.stream().filter(i -> i.getWorkStatus() != Issue.WorkStatus.BACKLOG
                && i.getPlannedWeekStart() != null && !i.getPlannedWeekStart().isAfter(week)
                && (i.getWorkStatus() != Issue.WorkStatus.DONE || within(i.getCompletedAt(), start, end))).toList();
        var completedIssues = issues.stream().filter(i -> i.getWorkStatus() == Issue.WorkStatus.DONE && within(i.getCompletedAt(), start, end)).toList();
        var states = new LinkedHashMap<String, Long>();
        for (var status : List.of(Issue.WorkStatus.IN_PROGRESS, Issue.WorkStatus.IN_REVIEW, Issue.WorkStatus.DONE))
            states.put(status.name(), scope.stream().filter(i -> boardStatus(i.getWorkStatus()) == status).count());

        var pulls = em.createQuery("select p from PullRequest p join fetch p.repository left join fetch p.authorAccount where p.repository.projectId=:id"
                + " and p.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED"
                + " and p.publishStatus=com.zello.zello.domain.common.PublishStatus.PUBLISHED and p.state=com.zello.zello.domain.pullrequest.PullRequest.State.OPEN and coalesce(p.openedAt,p.createdAt)<:end", PullRequest.class)
                .setParameter("id", projectId).setParameter("end", end).getResultList();
        var requests = em.createQuery("select r from PullRequestReviewer r join fetch r.pullRequest p join fetch r.reviewerAccount where p.repository.projectId=:id"
                + " and p.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED"
                + " and p.publishStatus=com.zello.zello.domain.common.PublishStatus.PUBLISHED and p.state=com.zello.zello.domain.pullrequest.PullRequest.State.OPEN and p.draft=false"
                + " and r.requestStatus=com.zello.zello.domain.pullrequest.PullRequestReviewer.RequestStatus.REQUESTED and r.requestedAt<:end and coalesce(p.openedAt,p.createdAt)<:end", PullRequestReviewer.class)
                .setParameter("id", projectId).setParameter("end", end).getResultList();
        var byPull = requests.stream().collect(Collectors.groupingBy(r -> r.getPullRequest().getId()));
        Map<Long, Optional<LocalDateTime>> requestDates = pulls.isEmpty() ? Map.of() : em.createQuery("select r.pullRequestId,max(r.submittedAt) from PullRequestReview r where r.pullRequestId in :ids and r.state='CHANGES_REQUESTED' group by r.pullRequestId", Object[].class)
                .setParameter("ids", pulls.stream().map(PullRequest::getId).toList()).getResultList().stream().collect(Collectors.toMap(r -> (Long) r[0], r -> Optional.ofNullable((LocalDateTime) r[1])));
        var attention = new ArrayList<Attention>();
        for (var pull : pulls) {
            if (pull.getDraft()) continue;
            var reviewers = byPull.getOrDefault(pull.getId(), List.of());
            if (pull.getReviewDecision() == PullRequest.ReviewDecision.CHANGES_REQUESTED) {
                var date = requestDates.getOrDefault(pull.getId(), Optional.empty()).orElse(null);
                Long days = age(date, now);
                attention.add(new Attention(pull.getId().toString(), "CHANGES_REQUESTED", pull.getTitle(), pull.getRepository().getName(),
                        login(pull.getAuthorAccount()), days, days == null ? "수정 요청" : "수정 요청 후 " + days + "일", List.of()));
            } else if (pull.getReviewDecision() == PullRequest.ReviewDecision.PENDING || !reviewers.isEmpty()) {
                var date = reviewers.stream().map(PullRequestReviewer::getRequestedAt).min(Comparator.naturalOrder()).orElse(pull.getOpenedAt());
                Long days = age(date, now);
                if (days != null && days >= 3) attention.add(new Attention(pull.getId().toString(), "REVIEW_AGING", pull.getTitle(), pull.getRepository().getName(), login(pull.getAuthorAccount()),
                        days, (reviewers.isEmpty() ? "PR 열린 지 " : "검토 요청 ") + days + "일", reviewers.stream().map(r -> login(r.getReviewerAccount())).toList()));
            }
        }
        for (var issue : scope) if (issue.getAssigneeAccountId() == null && issue.getWorkStatus() != Issue.WorkStatus.DONE)
            attention.add(new Attention(issue.getId().toString(), "UNASSIGNED", issue.getTitle(), issue.getRepository().getName(), null, null, "담당자 지정 필요", List.of()));
        attention.sort(Comparator.comparingInt((Attention a) -> switch (a.kind()) { case "CHANGES_REQUESTED" -> 0; case "REVIEW_AGING" -> 1; default -> 2; })
                .thenComparing(Comparator.comparingLong((Attention a) -> a.ageDays() == null ? 0 : a.ageDays()).reversed()).thenComparing(Attention::id));
        var counts = new AttentionCounts(attention.stream().filter(a -> a.kind().equals("UNASSIGNED")).count(), attention.stream().filter(a -> a.kind().equals("REVIEW_AGING")).count(), attention.stream().filter(a -> a.kind().equals("CHANGES_REQUESTED")).count());

        var merged = em.createQuery("select p from PullRequest p join fetch p.repository left join fetch p.authorAccount where p.repository.projectId=:id"
                + " and p.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED"
                + " and p.publishStatus=com.zello.zello.domain.common.PublishStatus.PUBLISHED and p.state=com.zello.zello.domain.pullrequest.PullRequest.State.MERGED"
                + " and p.mergedAt>=:start and p.mergedAt<:end and not exists (select l.id from IssuePullRequest l where l.id.pullRequestId=p.id)", PullRequest.class)
                .setParameter("id", projectId).setParameter("start", start).setParameter("end", end).getResultList();
        var sourceKeys = new ArrayList<String>();
        completedIssues.forEach(i -> sourceKeys.add("issue:" + i.getId()));
        merged.forEach(p -> sourceKeys.add("pr:" + p.getId()));
        Map<String, WorkChange> changes = sourceKeys.isEmpty() ? Map.of() : em.createQuery("select c from WorkChange c where c.projectId=:p and c.sourceKey in :keys and c.status in ('COMPLETED','MERGED')", WorkChange.class)
                .setParameter("p", projectId).setParameter("keys", sourceKeys).getResultList().stream().collect(Collectors.toMap(WorkChange::getSourceKey, Function.identity()));
        var recent = new ArrayList<Completed>();
        for (var i : completedIssues) {
            var change = changes.get("issue:" + i.getId());
            recent.add(new Completed(i.getId().toString(), "ISSUE", authoredTitle(change, i.getTitle()), login(i.getAssigneeAccount()), i.getRepository().getName(), i.getCompletedAt(), change == null || !change.isTitleWritten()));
        }
        for (var p : merged) {
            var change = changes.get("pr:" + p.getId());
            recent.add(new Completed(p.getId().toString(), "PR", authoredTitle(change, p.getTitle()), login(p.getAuthorAccount()), p.getRepository().getName(), p.getMergedAt(), change == null || !change.isTitleWritten()));
        }
        recent.sort(Comparator.comparing(Completed::completedAt).reversed().thenComparing(Completed::id));

        var members = em.createQuery("select m from ProjectMember m join fetch m.user u join fetch u.githubAccount where m.id.projectId=:p and m.leftAt is null and u.status=com.zello.zello.domain.user.User.Status.ACTIVE", ProjectMember.class)
                .setParameter("p", projectId).getResultList();
        var accounts = new LinkedHashMap<Long, GitHubAccount>();
        var jobs = new HashMap<Long, String>();
        for (var m : members) { var a = m.getUser().getGithubAccount(); accounts.put(a.getId(), a); jobs.put(a.getId(), m.getJobTitle()); }
        for (var i : scope) if (i.getAssigneeAccount() != null) accounts.putIfAbsent(i.getAssigneeAccountId(), i.getAssigneeAccount());
        for (var i : completedIssues) if (i.getAssigneeAccount() != null) accounts.putIfAbsent(i.getAssigneeAccountId(), i.getAssigneeAccount());
        for (var p : merged) if (p.getAuthorAccount() != null) accounts.putIfAbsent(p.getAuthorAccountId(), p.getAuthorAccount());
        for (var r : requests) accounts.putIfAbsent(r.getReviewerAccount().getId(), r.getReviewerAccount());
        var people = new ArrayList<Person>();
        for (var a : accounts.values()) {
            var owned = scope.stream().filter(i -> Objects.equals(i.getAssigneeAccountId(), a.getId())).toList();
            var assigned = owned.stream().filter(i -> i.getWorkStatus() != Issue.WorkStatus.DONE).sorted(Comparator.comparingInt(i -> boardStatus(i.getWorkStatus()) == Issue.WorkStatus.IN_PROGRESS ? 0 : i.getWorkStatus() == Issue.WorkStatus.IN_REVIEW ? 1 : 2)).toList();
            var reviewing = requests.stream().filter(r -> r.getReviewerAccount().getId().equals(a.getId())).toList();
            var tasks = new ArrayList<Task>();
            assigned.forEach(i -> tasks.add(new Task(i.getId().toString(), i.getTitle(), "ISSUE", boardStatus(i.getWorkStatus()).name(), i.getRepository().getName())));
            reviewing.forEach(r -> { var p = r.getPullRequest(); tasks.add(new Task(p.getId().toString(), p.getTitle(), "PR", "REVIEW_REQUESTED", p.getRepository().getName())); });
            long done = completedIssues.stream().filter(i -> Objects.equals(i.getAssigneeAccountId(), a.getId())).count() + merged.stream().filter(p -> Objects.equals(p.getAuthorAccountId(), a.getId())).count();
            people.add(new Person(a.getLogin(), a.getDisplayName() == null || a.getDisplayName().isBlank() ? a.getLogin() : a.getDisplayName(), a.getAvatarUrl(), jobs.get(a.getId()),
                    owned.stream().filter(i -> boardStatus(i.getWorkStatus()) == Issue.WorkStatus.IN_PROGRESS).count(), reviewing.size(), done, assigned.size(), tasks.stream().limit(12).toList()));
        }
        people.sort(Comparator.comparingLong((Person p) -> p.assigned() + p.reviewing()).reversed().thenComparing(Person::login));
        long done = states.get("DONE");
        long carry = scope.stream().filter(i -> i.getWorkStatus() != Issue.WorkStatus.DONE && i.getPlannedWeekStart().isBefore(week)).count();
        return new Dashboard(week, endDate.minusDays(1), period, project.getTimezone(), scope.size(), done, scope.isEmpty() ? 0 : (int) Math.round(done * 100.0 / scope.size()), carry,
                recent.size(), states, counts, attention, people, recent.stream().limit(5).toList(), recent.size(), now, currentWeek, project.getStartDate(), project.getEndDate());
    }

    private static boolean within(LocalDateTime date, LocalDateTime start, LocalDateTime end) { return date != null && !date.isBefore(start) && date.isBefore(end); }
    private static Long age(LocalDateTime date, LocalDateTime now) { return date == null ? null : Math.max(0, ChronoUnit.DAYS.between(date, now)); }
    private static String login(GitHubAccount account) { return account == null ? null : account.getLogin(); }
    private static String authoredTitle(WorkChange change, String original) { return change != null && change.isTitleWritten() ? change.getTitle() : original; }
}
