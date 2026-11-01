-- Only backfill legacy rows that have never been explicitly scheduled.
-- Creation time is stored in UTC; the product week follows Asia/Seoul.
UPDATE issues
SET planned_week_start = DATE_SUB(DATE(DATE_ADD(created_at, INTERVAL 9 HOUR)), INTERVAL WEEKDAY(DATE_ADD(created_at, INTERVAL 9 HOUR)) DAY)
WHERE planned_week_start = DATE_SUB(DATE(created_at), INTERVAL WEEKDAY(created_at) DAY)
  AND DATE_SUB(DATE(DATE_ADD(created_at, INTERVAL 9 HOUR)), INTERVAL WEEKDAY(DATE_ADD(created_at, INTERVAL 9 HOUR)) DAY) <> planned_week_start
  AND updated_at = created_at;
