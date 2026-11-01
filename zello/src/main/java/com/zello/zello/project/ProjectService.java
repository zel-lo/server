package com.zello.zello.project;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.user.UserRepository;
import java.util.List;
import java.util.Objects;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final UserRepository users;

    public ProjectService(ProjectRepository projects, ProjectMemberRepository members, UserRepository users) {
        this.projects = projects;
        this.members = members;
        this.users = users;
    }

    public List<ProjectView> list(long userId) {
        return members.findByIdUserIdAndLeftAtIsNullOrderByJoinedAtAsc(userId).stream()
                .filter(member -> member.getProject().getArchivedAt() == null)
                .map(member -> view(member.getProject(), member, userId)).toList();
    }

    @Transactional
    public ProjectView create(long userId, String name, String description) {
        return create(userId,name,description,null,null,null);
    }

    @Transactional
    public ProjectView create(long userId,String name,String description,LocalDate start,LocalDate end,String requestKey) {
        ProjectWeeks.validatePeriod(start,end);
        if(requestKey!=null && start==null) throw new ApiException(400,"PROJECT_PERIOD_REQUIRED","프로젝트 시작일과 종료일을 입력해 주세요.");
        var user = users.lockForProjectCreation(userId).orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."));
        String normalizedDescription=description==null ? null : description.strip();
        if(requestKey!=null) {
            var previous=projects.findByCreationRequestKey(requestKey);
            if(previous.isPresent()) {
                var project=previous.get();
                if(!project.getOwnerUserId().equals(userId) || !project.getName().equals(name.strip()) || !Objects.equals(project.getDescription(),normalizedDescription)
                    || !Objects.equals(project.getStartDate(),start) || !Objects.equals(project.getEndDate(),end))
                    throw new ApiException(409,"REQUEST_KEY_CONFLICT","이미 다른 프로젝트 생성에 사용한 요청이에요.");
                return detail(project.getId(),userId);
            }
        }
        Project project = projects.saveAndFlush(Project.builder().ownerUser(user).name(name.strip())
                .description(normalizedDescription).startDate(start).endDate(end).creationRequestKey(requestKey).build());
        var member = members.save(ProjectMember.builder().project(project).user(user).role(ProjectMember.Role.ADMIN).build());
        return view(project, member, userId);
    }

    public ProjectView detail(long projectId, long userId) {
        var project = requireMember(projectId, userId, false);
        var member = members.findByIdAndLeftAtIsNull(new ProjectMemberId(projectId, userId)).orElseThrow(ProjectService::notFound);
        return view(project, member, userId);
    }

    public Project requireMember(long projectId, long userId, boolean manage) {
        var member = members.findByIdAndLeftAtIsNull(new ProjectMemberId(projectId, userId))
                .orElseThrow(ProjectService::notFound);
        var project = projects.findById(projectId).filter(p -> p.getArchivedAt() == null).orElseThrow(ProjectService::notFound);
        if (manage && !project.getOwnerUserId().equals(userId) && member.getRole() != ProjectMember.Role.ADMIN) {
            throw new ApiException(403, "PROJECT_ADMIN_REQUIRED", "프로젝트 소유자 또는 관리자만 관리할 수 있습니다.");
        }
        return project;
    }

    /** 프로젝트 소유자나 VIEWER가 아닌 멤버만 GitHub에 쓰는 작업을 할 수 있다. */
    public Project requireWriter(long projectId, long userId) {
        var project = requireMember(projectId, userId, false);
        var member = members.findByIdAndLeftAtIsNull(new ProjectMemberId(projectId, userId)).orElseThrow(ProjectService::notFound);
        if (!project.getOwnerUserId().equals(userId) && member.getRole() == ProjectMember.Role.VIEWER) {
            throw new ApiException(403, "PROJECT_WRITE_REQUIRED", "조회 전용 멤버는 작업을 변경할 수 없습니다.");
        }
        return project;
    }

    public static ApiException notFound() {
        return new ApiException(404, "PROJECT_NOT_FOUND", "프로젝트를 찾을 수 없습니다.");
    }

    private ProjectView view(Project project, ProjectMember member, long userId) {
        boolean owner = project.getOwnerUserId().equals(userId);
        return new ProjectView(project.getId().toString(), project.getName(), project.getDescription(),
                owner ? "OWNER" : member.getRole().name(), owner || member.getRole() == ProjectMember.Role.ADMIN, project.getVersion(),project.getStartDate(),project.getEndDate(),ProjectWeeks.count(project));
    }

    public record ProjectView(String id, String name, String description, String role, boolean canManageRepositories, Long version,LocalDate startDate,LocalDate endDate,int totalWeeks) { }
}
