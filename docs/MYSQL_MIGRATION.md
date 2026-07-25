# PostgreSQL To MySQL Cutover

## Scope

BuddyStudy runtime persistence uses MySQL 8.4. Flyway creates new databases
from `backend/tutor/src/main/resources/db/migration-mysql`. The old PostgreSQL
migrations remain in the repository only as historical schema evidence; they
are not executed by the MySQL runtime.

Public question search reads the canonical `questions` table directly. There
is no `question_search` projection to migrate, rebuild, or reconcile.

## Safety Rules

- Do not point a production backend at an empty MySQL database.
- Do not attach a PostgreSQL data directory to a MySQL container.
- Keep the PostgreSQL volume read-only and available until the rollback window
  has ended.
- Stop application and scheduler writes before the final export.
- Never place database passwords in dump files, shell history, or Git.

The backend deploy template checks the existing `buddystudy-db` image and
stops when it is still PostgreSQL. That guard prevents an accidental in-place
engine replacement.

## Cutover Sequence

1. Create a fresh MySQL 8.4 database and run Flyway through
   `V2__seed_policy_data.sql`.
2. Perform a rehearsal export from a PostgreSQL snapshot.
3. Convert PostgreSQL values to the MySQL schema:
   - `timestamptz` values become UTC `datetime(6)` values.
   - booleans become `0` or `1`.
   - JSON/array-like values are serialized as valid UTF-8 text where the MySQL
     schema uses `text`.
   - sequence-backed IDs are retained as explicit `bigint` values.
4. Import parent tables before dependent tables. Disable no foreign-key checks
   during the final validated import unless the import tool guarantees ordering.
5. For every auto-increment table, set the next value above the imported
   maximum ID.
6. Compare source and destination row counts for every canonical table.
7. Validate critical invariants:
   - every question references an existing user/study when the key is present;
   - active sessions and devices retain their owner;
   - study schedules and pending jobs retain UTC timestamps;
   - terms, permissions, quotas, notification preferences, and outbox rows
     preserve their natural keys;
   - public question counts and a representative full-text search agree with
     the canonical `questions` rows.
8. Run authenticated studies, public questions, login, terms, notifications,
   question creation, grading, and scheduler checks against MySQL.
9. Update AWS Secrets Manager secret `buddystudy/prod/mysql` with `dbname`,
   `username`, `password`, `jdbcUrl`, and `r2dbcUrl`.
10. Deploy the backend, watch error rate and database connection pressure, then
    keep PostgreSQL read-only for the agreed rollback period.

## Rollback

If validation fails before new MySQL writes begin, restore the previous backend
configuration and PostgreSQL container. If MySQL has accepted writes, rollback
requires a planned reverse data reconciliation; do not simply switch the
connection URL because that would lose MySQL-only writes.

## Backups

Use `mysqldump --single-transaction --quick --routines --triggers` for logical
backups. Restore a compressed snapshot with:

```sh
gunzip -c buddystudy-<timestamp>.sql.gz | \
  MYSQL_PWD="<password>" mysql -h <host> -P <port> -u buddystudy buddystudy
```

Test restores on a disposable MySQL instance. A backup is not considered valid
until a restore and row-count verification have succeeded.
