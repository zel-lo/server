package com.zello.zello.project;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.user.UserRepository;
import java.util.List;
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
        var user = users.findById(userId).orElseThrow(() -> new ApiException(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."));
        Project project = projects.saveAndFlush(Project.builder().ownerUser(user).name(name.strip())
                .description(description == null ? null : description.strip()).build());
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
                owner ? "OWNER" : member.getRole().name(), owner || member.getRole() == ProjectMember.Role.ADMIN, project.getVersion());
    }

    public record ProjectView(String id, String name, String description, String role, boolean canManageRepositories, Long version) { }
}
