"""Exercise ERD relational constraints in an isolated, in-memory SQLite database.

Only MySQL-specific table options, timestamps, indexes and identity syntax are
adapted. This checks constraint logic, not MySQL engine compatibility or API
behavior. Run: python3 docs/checks/verify_erd.py from server/zello.
"""
from pathlib import Path
import re
import sqlite3
import unittest

SCHEMA = Path(__file__).resolve().parents[1] / 'erd.sql'
NOW = '2026-10-30 10:00:00'
LATER = '2026-10-31 10:00:00'


def portable_schema():
    statements = re.findall(r'CREATE TABLE \w+ \([\s\S]*?\) ENGINE=InnoDB[^;]*;', SCHEMA.read_text())
    assert len(statements) == 21
    result = []
    for statement in statements:
        statement = re.sub(r'\) ENGINE=InnoDB[^;]*;', ');', statement)
        statement = re.sub(r'^    KEY [^\n]+\n', '', statement, flags=re.M)
        if 'id BIGINT NOT NULL AUTO_INCREMENT' in statement:
            statement = statement.replace('id BIGINT NOT NULL AUTO_INCREMENT', 'id INTEGER PRIMARY KEY AUTOINCREMENT', 1)
            statement = statement.replace('    PRIMARY KEY (id),\n', '', 1)
        statement = statement.replace(' ON UPDATE CURRENT_TIMESTAMP(6)', '')
        statement = statement.replace('CURRENT_TIMESTAMP(6)', 'CURRENT_TIMESTAMP')
        statement = statement.replace(' COLLATE utf8mb4_bin', '')
        result.append(statement)
    return '\n'.join(result)


class ConstraintChecks(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript(portable_schema())
        for i in range(1, 4):
            self.add('github_accounts', id=i, github_id=100+i, login=f'user{i}', account_type='USER')
            self.add('users', id=i, github_account_id=i, theme='light')
        for i in (1, 2):
            self.add('projects', id=i, owner_user_id=i, name=f'project{i}', timezone='Asia/Seoul', sync_interval_minutes=15)
            self.add_member(i, i)
        self.add('github_installations', id=1, github_installation_id=100, owner_account_id=1, status='ACTIVE', permissions='{}', installed_at=NOW)
        for repo, project in ((1, 1), (2, 1), (3, 2)):
            self.add('repositories', id=repo, project_id=project, installation_id=1, github_repo_id=100+repo, owner_login='team', name=f'repo{repo}', html_url='https://example.test', default_branch='main', is_private=True, connection_status='CONNECTED')
            self.issue(id=repo, repository_id=repo)
            self.pr(id=repo, repository_id=repo)
        self.add('patch_notes', id=1, project_id=1, author_user_id=1, title='Update 1', status='DRAFT')
        self.add('patch_notes', id=2, project_id=2, author_user_id=2, title='Update 2', status='DRAFT')
        self.add('build_runs', id=1, repository_id=1, github_run_id=101, run_attempt=1, workflow_name='CI', head_sha='a'*40, status='completed', conclusion='success', html_url='https://example.test')
        self.add('deployments', id=1, repository_id=1, github_deployment_id=101, environment='production', commit_sha='a'*40, status='success')
        self.activity(id=1, repository_id=1, issue_id=1)

    def tearDown(self):
        self.db.close()

    def add(self, table, **values):
        cols = ', '.join(values)
        placeholders = ', '.join('?' for _ in values)
        return self.db.execute(f'INSERT INTO {table} ({cols}) VALUES ({placeholders})', tuple(values.values())).lastrowid

    def add_member(self, project, user):
        self.add('project_members', project_id=project, user_id=user, role='MEMBER', notify_team_updates=True, notify_deployments=True, weekly_summary_enabled=True)

    def issue(self, **overrides):
        values = dict(repository_id=1, issue_key=f'ZELLO-{overrides.get("id", 100)}', title='Work', work_status='TODO', priority='MEDIUM', source_type='REQUEST', publish_status='LOCAL')
        values.update(overrides)
        return self.add('issues', **values)

    def pr(self, **overrides):
        values = dict(repository_id=1, title='Change', head_branch='feat/test', base_branch='main', state='OPEN', is_draft=False, review_decision='UNKNOWN', publish_status='LOCAL')
        values.update(overrides)
        return self.add('pull_requests', **values)

    def activity(self, **overrides):
        values = dict(project_id=1, event_key=f'event:{overrides.get("id", 100)}', event_type='UPDATED', title='Update', occurred_at=NOW)
        values.update(overrides)
        return self.add('activities', **values)

    def invalid(self, fn, reason):
        with self.assertRaisesRegex(sqlite3.IntegrityError, reason):
            fn()

    def test_valid_scoped_links(self):
        self.add('issue_pull_requests', repository_id=1, issue_id=1, pull_request_id=1)
        self.add('deployment_pull_requests', repository_id=1, deployment_id=1, pull_request_id=1, linked_at=NOW)
        self.add('patch_note_items', project_id=1, repository_id=1, patch_note_id=1, pull_request_id=1, category='FEATURE', title='Feature', sort_order=0)

    def test_issue_pr_cannot_cross_repository_or_project(self):
        for pr in (2, 3):
            with self.subTest(pr=pr):
                self.invalid(lambda: self.add('issue_pull_requests', repository_id=1, issue_id=1, pull_request_id=pr), 'FOREIGN KEY')

    def test_forged_repository_does_not_bypass_issue_scope(self):
        self.invalid(lambda: self.add('issue_pull_requests', repository_id=2, issue_id=1, pull_request_id=2), 'FOREIGN KEY')

    def test_deployment_cannot_link_unrelated_pr(self):
        self.invalid(lambda: self.add('deployment_pull_requests', repository_id=1, deployment_id=1, pull_request_id=3, linked_at=NOW), 'FOREIGN KEY')

    def test_patch_note_enforces_both_project_paths(self):
        for values in [dict(project_id=1, repository_id=3, patch_note_id=1, pull_request_id=3), dict(project_id=1, repository_id=1, patch_note_id=2, pull_request_id=1)]:
            with self.subTest(values=values):
                self.invalid(lambda: self.add('patch_note_items', **values, category='FIX', title='Fix', sort_order=0), 'FOREIGN KEY')

    def test_activity_project_must_own_repository(self):
        self.invalid(lambda: self.activity(repository_id=3, issue_id=3), 'FOREIGN KEY')

    def test_activity_target_must_belong_to_repository(self):
        self.invalid(lambda: self.activity(repository_id=1, issue_id=3), 'FOREIGN KEY')

    def test_null_repository_cannot_bypass_activity_scope(self):
        for target in ['issue_id', 'pull_request_id', 'build_run_id', 'deployment_id']:
            with self.subTest(target=target):
                self.invalid(lambda: self.activity(**{target: 1}), 'ck_activities_repository_target')

    def test_activity_accepts_each_valid_target(self):
        for i, target in enumerate(['issue_id', 'pull_request_id', 'build_run_id', 'deployment_id'], 2):
            self.activity(id=i, repository_id=1, **{target: 1})
        self.activity(id=10, patch_note_id=1)
        self.activity(id=11, repository_id=1, commit_sha='a'*40)

    def test_activity_single_target_and_patch_note_project(self):
        self.invalid(lambda: self.activity(repository_id=1, issue_id=1, pull_request_id=1), 'ck_activities_single_target')
        self.invalid(lambda: self.activity(project_id=2, patch_note_id=1), 'FOREIGN KEY')
        self.invalid(lambda: self.activity(repository_id=1, patch_note_id=1), 'ck_activities_patch_note_scope')

    def test_notification_requires_membership_in_activity_project(self):
        for project, user in [(1, 2), (2, 2)]:
            with self.subTest(project=project):
                self.invalid(lambda: self.add('notifications', project_id=project, recipient_user_id=user, activity_id=1, title='Notice'), 'FOREIGN KEY')

    def test_notification_is_unique_and_read_state_is_individual(self):
        self.add_member(1, 2)
        for user in (1, 2):
            self.add('notifications', project_id=1, recipient_user_id=user, activity_id=1, title='Notice')
        self.invalid(lambda: self.add('notifications', project_id=1, recipient_user_id=1, activity_id=1, title='Duplicate'), 'UNIQUE')
        self.db.execute('UPDATE notifications SET read_at=? WHERE recipient_user_id=1', (NOW,))
        self.assertIsNone(self.db.execute('SELECT read_at FROM notifications WHERE recipient_user_id=2').fetchone()[0])

    def test_withdrawal_state_requires_matching_timestamp(self):
        self.invalid(lambda: self.db.execute("UPDATE users SET status='WITHDRAWN' WHERE id=3"), 'ck_users_withdrawal')
        self.invalid(lambda: self.db.execute('UPDATE users SET withdrawn_at=? WHERE id=3', (NOW,)), 'ck_users_withdrawal')
        self.db.execute("UPDATE users SET status='WITHDRAWN', withdrawn_at=? WHERE id=3", (NOW,))
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM github_accounts WHERE id=3').fetchone()[0], 1)

    def test_publication_requires_external_identity(self):
        self.invalid(lambda: self.issue(publish_status='PUBLISHED'), 'ck_issues_published')
        self.invalid(lambda: self.pr(publish_status='PUBLISHED'), 'ck_pull_requests_published')
        self.invalid(lambda: self.issue(github_issue_id=101), 'ck_issues_external_identity')
        self.invalid(lambda: self.pr(github_pr_id=101), 'ck_pull_requests_external_identity')
        self.issue(publish_status='PUBLISHED', github_issue_id=1001, github_number=42, github_state='OPEN')
        self.pr(publish_status='PUBLISHED', github_pr_id=1002, github_number=43)

    def test_merged_pr_requires_complete_non_draft_publication(self):
        self.invalid(lambda: self.pr(state='MERGED'), 'ck_pull_requests_merge')
        self.invalid(lambda: self.pr(state='OPEN', merged_at=NOW), 'ck_pull_requests_merge')
        self.invalid(lambda: self.pr(state='MERGED', merged_at=NOW, is_draft=True, publish_status='PUBLISHED', github_pr_id=101, github_number=44), 'ck_pull_requests_merge')
        self.pr(state='MERGED', merged_at=NOW, is_draft=False, publish_status='PUBLISHED', github_pr_id=101, github_number=44)

    def test_patch_note_publication_has_timestamp(self):
        self.invalid(lambda: self.db.execute("UPDATE patch_notes SET status='PUBLISHED' WHERE id=1"), 'ck_patch_notes_publication')
        self.db.execute("UPDATE patch_notes SET status='PUBLISHED', published_at=? WHERE id=1", (NOW,))

    def test_invitation_acceptance_pair_and_expiry(self):
        base = dict(project_id=1, invited_by_user_id=1, token_hash='a'*64, granted_role='MEMBER', created_at=NOW, expires_at=LATER)
        self.invalid(lambda: self.add('project_invitations', **base, accepted_at=NOW), 'ck_project_invitations_acceptance')
        self.invalid(lambda: self.add('project_invitations', **base, accepted_by_user_id=2), 'ck_project_invitations_acceptance')
        self.invalid(lambda: self.add('project_invitations', **base, accepted_by_user_id=2, accepted_at=LATER), 'ck_project_invitations_accepted_at')
        self.invalid(lambda: self.add('project_invitations', **base, accepted_by_user_id=2, accepted_at=NOW, revoked_at=NOW), 'ck_project_invitations_terminal')
        self.add('project_invitations', **base, accepted_by_user_id=2, accepted_at=NOW)

    def test_initial_sync_suppresses_historical_notifications(self):
        self.invalid(lambda: self.add('repository_sync_runs', repository_id=1, run_kind='INITIAL', sync_scope='ALL', emit_notifications=True), 'ck_repository_sync_runs_initial')

    def test_sync_lifecycle_and_retry_stay_in_repository(self):
        base = dict(repository_id=1, run_kind='INITIAL', sync_scope='ALL')
        self.invalid(lambda: self.add('repository_sync_runs', **base, status='RUNNING'), 'ck_repository_sync_runs_lifecycle')
        self.invalid(lambda: self.add('repository_sync_runs', **base, status='SUCCEEDED', started_at=NOW), 'ck_repository_sync_runs_lifecycle')
        previous = self.add('repository_sync_runs', **base, status='FAILED', started_at=NOW, finished_at=LATER)
        self.add('repository_sync_runs', **base, retry_of_id=previous, attempt_no=2, checkpoint='{"issues":{"page":2}}')
        self.invalid(lambda: self.add('repository_sync_runs', repository_id=3, run_kind='INITIAL', sync_scope='ALL', retry_of_id=previous), 'FOREIGN KEY')

    def test_webhook_processing_needs_lease_and_completion(self):
        base = dict(github_delivery_id='test-delivery', event_name='issues', payload='{}', attempt_count=0)
        self.invalid(lambda: self.add('webhook_deliveries', **base, processing_status='PROCESSING'), 'ck_webhook_deliveries_lease')
        self.invalid(lambda: self.add('webhook_deliveries', **base, processing_status='PROCESSED'), 'ck_webhook_deliveries_processed')
        self.add('webhook_deliveries', **base, processing_status='PROCESSING', processing_started_at=NOW, lease_expires_at=LATER)

    def test_event_keys_are_case_sensitive(self):
        self.activity(id=20, event_key='branch:Feature/X')
        self.activity(id=21, event_key='branch:feature/x')


if __name__ == '__main__':
    print('Portable constraint checks (SQLite); not a MySQL engine execution test.', flush=True)
    unittest.main(verbosity=2)
