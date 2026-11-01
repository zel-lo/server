package com.zello.zello.project;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.project.Project;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** Project weeks are derived from a stored period; changing the clock never duplicates tasks. */
public final class ProjectWeeks {
    private ProjectWeeks() { }
    public static void validatePeriod(LocalDate start, LocalDate end) {
        if ((start == null) != (end == null) || start != null && (start.getYear()<1000 || end.getYear()>9999 || end.isBefore(start) || end.isAfter(start.plusYears(10).minusDays(1))))
            throw new ApiException(400,"INVALID_PROJECT_PERIOD","시작일과 종료일을 함께 입력해 주세요. 종료일은 시작일 이후, 기간은 10년 이내여야 해요.");
    }
    public static int count(Project project) {
        return project.getStartDate()==null ? 0 : (int)(ChronoUnit.DAYS.between(project.getStartDate(),project.getEndDate())/7)+1;
    }
    public static LocalDate current(Project project) {
        return containing(project, LocalDate.now(ZoneId.of(project.getTimezone())));
    }
    public static LocalDate containing(Project project, LocalDate date) {
        if(project.getStartDate()==null) return date.with(DayOfWeek.MONDAY);
        long index=Math.max(0,Math.min(count(project)-1,Math.floorDiv(ChronoUnit.DAYS.between(project.getStartDate(),date),7)));
        return project.getStartDate().plusWeeks(index);
    }
    public static void requireWeek(Project project,LocalDate week) {
        if(week==null) return;
        boolean valid=project.getStartDate()==null ? week.getDayOfWeek()==DayOfWeek.MONDAY
                : !week.isBefore(project.getStartDate()) && !week.isAfter(project.getEndDate()) && ChronoUnit.DAYS.between(project.getStartDate(),week)%7==0;
        if(!valid) throw new ApiException(400,"INVALID_WEEK","프로젝트 기간 안의 주차를 선택해 주세요.");
    }
    public static LocalDateTime begins(Project project,LocalDate week) {
        return week.atStartOfDay(ZoneId.of(project.getTimezone())).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    public static LocalDateTime ends(Project project,LocalDate week) {
        // The last project week remains available for finishing remaining work after the planned end.
        return project.getEndDate()!=null && !week.plusWeeks(1).isBefore(project.getEndDate().plusDays(1)) ? null : begins(project,week.plusWeeks(1));
    }
}
