package com.zello.zello.workspace;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.activity.Activity;
import com.zello.zello.domain.notification.Notification;
import com.zello.zello.domain.patchnote.PatchNote;
import com.zello.zello.domain.patchnote.WorkChange;
import com.zello.zello.domain.user.User;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.project.ProjectService;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {
    private final EntityManager em;
    private final ProjectService projects;
    private final WorkChangeService changes;
    public WorkspaceService(EntityManager em, ProjectService projects, WorkChangeService changes) { this.em=em; this.projects=projects; this.changes=changes; }
    public record Page<T>(List<T> items, int page, boolean hasNext) { }
    private int offset(int page) {
        if(page<1 || page>10000) throw new ApiException(400,"INVALID_PAGE","페이지를 확인해 주세요.");
        return (page-1)*30;
    }
    private <T> Page<T> page(List<T> rows,int page) { return new Page<>(rows.stream().limit(30).toList(),page,rows.size()>30); }
    public record ActivityView(String id,String type,String title,String summary,String actor,String repositoryId,String issueId,String pullRequestId,String patchNoteId,LocalDateTime occurredAt) { }
    private String id(Long id) { return id==null?null:id.toString(); }
    private ActivityView activity(Activity a) {
        return new ActivityView(id(a.getId()),a.getEventType(),a.getTitle(),a.getSummary(),a.getActorAccount()==null?null:a.getActorAccount().getLogin(),id(a.getRepositoryId()),id(a.getIssueId()),id(a.getPullRequestId()),id(a.getPatchNoteId()),a.getOccurredAt());
    }
    public Page<ActivityView> activities(long project,long user,int page) {
        projects.requireMember(project,user,false);
        var rows=em.createQuery("select a from Activity a left join fetch a.actorAccount where a.projectId=:p order by a.occurredAt desc,a.id desc",Activity.class).setParameter("p",project).setFirstResult(offset(page)).setMaxResults(31).getResultList();
        return page(rows.stream().map(this::activity).toList(),page);
    }
    public record NotificationView(String id,String title,String body,LocalDateTime readAt,ActivityView activity) { }
    public Page<NotificationView> notifications(long project,long user,int page,boolean unread) {
        projects.requireMember(project,user,false);
        var rows=em.createQuery("select n from Notification n join fetch n.activity a left join fetch a.actorAccount where n.projectId=:p and n.recipientUserId=:u"+(unread?" and n.readAt is null":"")+" order by n.id desc",Notification.class).setParameter("p",project).setParameter("u",user).setFirstResult(offset(page)).setMaxResults(31).getResultList();
        return page(rows.stream().map(n->new NotificationView(id(n.getId()),n.getTitle(),n.getBody(),n.getReadAt(),activity(n.getActivity()))).toList(),page);
    }
    @Transactional
    public void read(long project,long user,long notification,boolean read) {
        projects.requireMember(project,user,false);
        var n=em.find(Notification.class,notification);
        if(n==null || n.getProjectId()!=project || n.getRecipientUserId()!=user) throw new ApiException(404,"NOTIFICATION_NOT_FOUND","알림을 찾을 수 없어요.");
        if(read) n.markRead(EntitySupport.now()); else n.markUnread();
    }
    @Transactional
    public void readAll(long project,long user) {
        projects.requireMember(project,user,false);
        em.createQuery("update Notification n set n.readAt=:now where n.projectId=:p and n.recipientUserId=:u and n.readAt is null").setParameter("now",EntitySupport.now()).setParameter("p",project).setParameter("u",user).executeUpdate();
    }
    public record Summary(Map<String,Long> issues,long mine,long unread,long repositories,List<Day> contributions) { }
    public record Day(String date,long count) { }
    public Summary summary(long project,long user) {
        projects.requireMember(project,user,false);
        String scope=" from Issue i where i.repository.projectId=:p and i.repository.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED and i.archivedAt is null";
        var counts=new LinkedHashMap<String,Long>();
        for(var row:em.createQuery("select i.workStatus,count(i)"+scope+" group by i.workStatus",Object[].class).setParameter("p",project).getResultList()) counts.put(row[0].toString(),(Long)row[1]);
        long mine=em.createQuery("select count(i)"+scope+" and i.workStatus <> com.zello.zello.domain.issue.Issue.WorkStatus.DONE and i.assigneeAccountId=:a",Long.class).setParameter("p",project).setParameter("a",em.find(User.class,user).getGithubAccountId()).getSingleResult();
        long unread=em.createQuery("select count(n) from Notification n where n.projectId=:p and n.recipientUserId=:u and n.readAt is null",Long.class).setParameter("p",project).setParameter("u",user).getSingleResult();
        long repos=em.createQuery("select count(r) from GitRepository r where r.projectId=:p and r.connectionStatus <> com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED",Long.class).setParameter("p",project).getSingleResult();
        var days=em.createQuery("select year(a.occurredAt),month(a.occurredAt),day(a.occurredAt),count(a) from Activity a where a.projectId=:p and a.occurredAt>=:since group by year(a.occurredAt),month(a.occurredAt),day(a.occurredAt) order by year(a.occurredAt),month(a.occurredAt),day(a.occurredAt)",Object[].class).setParameter("p",project).setParameter("since",EntitySupport.now().minusYears(1)).getResultList().stream().map(r->new Day(String.format("%04d-%02d-%02d",r[0],r[1],r[2]),(Long)r[3])).toList();
        return new Summary(counts,mine,unread,repos,days);
    }
    public record SyncView(String id,String repository,String scope,String status,long processed,LocalDateTime finishedAt,String error) { }
    public Page<SyncView> syncRuns(long project,long user,int page) {
        projects.requireMember(project,user,false);
        var rows=em.createQuery("select r from RepositorySyncRun r join fetch r.repository where r.repository.projectId=:p order by r.id desc",com.zello.zello.domain.repository.RepositorySyncRun.class).setParameter("p",project).setFirstResult(offset(page)).setMaxResults(31).getResultList();
        return page(rows.stream().map(r->new SyncView(id(r.getId()),r.getRepository().getOwnerLogin()+"/"+r.getRepository().getName(),r.getSyncScope().name(),r.getStatus().name(),r.getItemsProcessed(),r.getFinishedAt(),r.getLastError())).toList(),page);
    }
    public NoteView noteDetail(long project,long user,long noteId) {
        projects.requireMember(project,user,false);
        var n=em.find(PatchNote.class,noteId);
        if(n==null || n.getProjectId()!=project) throw new ApiException(404,"NOTE_NOT_FOUND","패치노트를 찾을 수 없어요.");
        return note(n);
    }
    public record NoteView(String id,String title,String summary,String status,long version,String author,LocalDateTime publishedAt,LocalDateTime createdAt,boolean automatic,List<ChangeView> changes) { }
    public record ChangeView(String id,String issueId,String pullRequestId,String title,String summary,String category,String status,LocalDateTime occurredAt,boolean needsTitle) { }
    private NoteView note(PatchNote n) {
        var rows=em.createQuery("select c from WorkChange c where c.projectId=:p and c.patchNoteId=:n order by c.occurredAt,c.id",WorkChange.class).setParameter("p",n.getProjectId()).setParameter("n",n.getId()).getResultList();
        return new NoteView(id(n.getId()),n.getTitle(),n.getSummary(),n.getStatus().name(),n.getVersion(),n.getAuthorUser().getGithubAccount().getLogin(),n.getPublishedAt(),n.getCreatedAt(),n.getVersionLabel()!=null && n.getVersionLabel().startsWith("auto-work:"),
                rows.stream().map(c->new ChangeView(id(c.getId()),id(c.getIssueId()),id(c.getPullRequestId()),c.getTitle(),c.getSummary(),c.getCategory(),c.getStatus(),c.getOccurredAt(),!c.isTitleWritten())).toList());
    }
    @Transactional
    public NoteView writeChangeTitle(long project,long user,long noteId,long changeId,String title,long version) {
        em.find(com.zello.zello.domain.project.Project.class,project,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        projects.requireWriter(project,user);
        var entry=em.find(WorkChange.class,changeId);
        if(entry==null || entry.getProjectId()!=project || entry.getPatchNoteId()!=noteId)
            throw new ApiException(404,"CHANGE_NOT_FOUND","반영 사항을 찾을 수 없어요.");
        var n=em.find(PatchNote.class,noteId);
        if(!Objects.equals(n.getVersion(),version)) throw new ApiException(409,"VERSION_CONFLICT","다른 팀원이 수정했어요. 최신 내용을 확인해 주세요.");
        if(!List.of("COMPLETED","MERGED").contains(entry.getStatus()))
            throw new ApiException(409,"WORK_NOT_COMPLETED","완료된 작업의 반영 사항을 작성해 주세요.");
        changes.writeTitle(entry,title);
        em.flush();
        return note(n);
    }
    public Page<NoteView> notes(long project,long user,int page) {
        projects.requireMember(project,user,false);
        var rows=em.createQuery("select n from PatchNote n join fetch n.authorUser u join fetch u.githubAccount where n.projectId=:p order by n.id desc",PatchNote.class).setParameter("p",project).setFirstResult(offset(page)).setMaxResults(31).getResultList();
        return page(rows.stream().map(this::note).toList(),page);
    }
    @Transactional
    public NoteView saveNote(long project,long user,Long noteId,String title,String summary,Long version,boolean publish) {
        em.find(com.zello.zello.domain.project.Project.class,project,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var p=projects.requireWriter(project,user);
        PatchNote n;
        if(noteId==null) { n=PatchNote.builder().project(p).authorUser(em.find(User.class,user)).title(title.strip()).summary(summary).build(); em.persist(n); }
        else {
            n=em.find(PatchNote.class,noteId);
            if(n==null || n.getProjectId()!=project) throw new ApiException(404,"NOTE_NOT_FOUND","패치노트를 찾을 수 없어요.");
            if(!Objects.equals(n.getVersion(),version)) throw new ApiException(409,"VERSION_CONFLICT","다른 팀원이 수정했어요. 최신 내용을 확인해 주세요.");
            n.edit(title.strip(),summary);
        }
        if(publish && n.getStatus()!=PatchNote.Status.PUBLISHED && n.getVersionLabel()!=null && n.getVersionLabel().startsWith("auto-work:")) {
            long included=em.createQuery("select count(c) from WorkChange c where c.patchNoteId=:n and c.status in ('COMPLETED','MERGED')",Long.class).setParameter("n",n.getId()).getSingleResult();
            if(included==0) throw new ApiException(409,"NO_COMPLETED_CHANGES","공개할 완료 작업이 없어요. 작업 상태를 다시 확인해 주세요.");
            long missing=em.createQuery("select count(c) from WorkChange c where c.patchNoteId=:n and c.status in ('COMPLETED','MERGED') and c.titleWritten=false",Long.class).setParameter("n",n.getId()).getSingleResult();
            if(missing>0) throw new ApiException(409,"CHANGE_TITLE_REQUIRED","반영 사항 작성 필요: 패치노트에 포함할 제목을 먼저 작성해 주세요.");
        }
        boolean firstPublication=publish && n.getStatus()!=PatchNote.Status.PUBLISHED;
        if(firstPublication) n.publish(EntitySupport.now());
        em.flush();
        if(firstPublication) em.persist(Activity.builder().project(p).actorAccount(em.find(User.class,user).getGithubAccount()).patchNote(n).eventKey("patch-note-published:"+n.getId()).eventType("PATCH_NOTE_PUBLISHED").title(n.getTitle()).summary(n.getSummary()).occurredAt(EntitySupport.now()).build());
        return note(n);
    }
}
