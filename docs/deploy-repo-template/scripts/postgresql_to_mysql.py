#!/usr/bin/env python3

import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


TABLE_ORDER = [
    "users",
    "devices",
    "user_devices",
    "studies",
    "study_question_concepts",
    "questions",
    "question_stats",
    "question_likes",
    "question_comments",
    "question_embeddings",
    "study_question_coverage",
    "study_question_jobs",
    "app_notifications",
    "reports",
    "roles",
    "permissions",
    "user_roles",
    "role_permissions",
    "terms",
    "user_term_agreements",
    "term_context_requirements",
    "permission_requirements",
    "notification_preferences",
    "user_membership_tiers",
    "user_memberships",
    "user_monthly_question_usage",
    "user_stats",
    "user_stats_dirty_keys",
    "admin_daily_metrics",
    "scheduled_jobs",
    "scheduled_job_runs",
    "redis_event_outbox",
    "avatar_categories",
    "avatar_items",
    "user_avatar_items",
]

COLUMN_ALIASES = {
    ("user_monthly_question_usage", "usage_month"): "year_month",
}

NULLABLE_REFERENCE_NORMALIZATIONS = {
    ("questions", "user_id"): ("users", "id"),
    ("questions", "study_id"): ("studies", "id"),
    ("questions", "concept_id"): ("study_question_concepts", "id"),
}

NULL_MARKER = f"__BUDDYSTUDY_NULL_{uuid.uuid4().hex}__"


def run(command: list[str], *, capture: bool = True, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        command,
        check=True,
        capture_output=capture,
        text=text,
    )


def docker_env(container: str) -> dict[str, str]:
    result = run(["docker", "inspect", "-f", "{{json .Config.Env}}", container])
    values = {}
    for entry in json.loads(result.stdout):
        key, _, value = entry.partition("=")
        values[key] = value
    return values


POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "buddystudy-db-postgres-rollback")
MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "buddystudy-db")
MYSQL_DATABASE = os.environ.get("MYSQL_DATABASE", "buddystudy")
MYSQL_USER = os.environ.get("MYSQL_USER", "buddystudy")
MYSQL_PASSWORD = os.environ["MYSQL_PASSWORD"]
WORK_DIR = Path(os.environ.get("MIGRATION_WORK_DIR", "/tmp/buddystudy-db-cutover"))
WORK_DIR.mkdir(parents=True, exist_ok=True)

pg_env = docker_env(POSTGRES_CONTAINER)
PG_DATABASE = pg_env.get("POSTGRES_DB", "buddystudy")
PG_USER = pg_env.get("POSTGRES_USER", "buddystudy")


def pg_query(sql: str) -> str:
    result = run(
        [
            "docker",
            "exec",
            POSTGRES_CONTAINER,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            PG_USER,
            "-d",
            PG_DATABASE,
            "-A",
            "-t",
            "-F",
            "\t",
            "-c",
            sql,
        ]
    )
    return result.stdout.rstrip("\r\n")


def mysql_query(sql: str) -> str:
    result = run(
        [
            "docker",
            "exec",
            "-e",
            f"MYSQL_PWD={MYSQL_PASSWORD}",
            MYSQL_CONTAINER,
            "mysql",
            "--local-infile=1",
            "--default-character-set=utf8mb4",
            "--batch",
            "--skip-column-names",
            "-u",
            MYSQL_USER,
            MYSQL_DATABASE,
            "-e",
            sql,
        ]
    )
    return result.stdout.rstrip("\r\n")


def parse_tsv(raw: str) -> list[list[str]]:
    if not raw:
        return []
    return list(csv.reader(raw.splitlines(), delimiter="\t"))


def quote_pg(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def quote_mysql(identifier: str) -> str:
    return "`" + identifier.replace("`", "``") + "`"


def source_column_name(table: str, destination_column: str) -> str:
    return COLUMN_ALIASES.get((table, destination_column), destination_column)


source_tables = {
    row[0]
    for row in parse_tsv(
        pg_query(
            """
            select table_name
              from information_schema.tables
             where table_schema = 'public'
               and table_type = 'BASE TABLE'
             order by table_name
            """
        )
    )
}
destination_tables = {
    row[0]
    for row in parse_tsv(
        mysql_query(
            """
            select table_name
              from information_schema.tables
             where table_schema = database()
               and table_type = 'BASE TABLE'
             order by table_name
            """
        )
    )
}

migration_tables = [
    table for table in TABLE_ORDER if table in source_tables and table in destination_tables
]
unknown_source_tables = sorted(
    source_tables - destination_tables - {"flyway_schema_history", "question_search"}
)
if unknown_source_tables:
    raise RuntimeError(
        "Source tables have no MySQL destination: " + ", ".join(unknown_source_tables)
    )

print(f"Migrating {len(migration_tables)} canonical tables.")
delete_statements = "; ".join(
    f"delete from {quote_mysql(table)}" for table in reversed(migration_tables)
)
mysql_query(
    f"set foreign_key_checks = 0; {delete_statements}; "
    "set foreign_key_checks = 1"
)

counts: dict[str, int] = {}
normalized_references: dict[str, int] = {}
for table in migration_tables:
    source_column_rows = parse_tsv(
        pg_query(
            f"""
            select column_name, data_type, udt_name
              from information_schema.columns
             where table_schema = 'public'
               and table_name = '{table}'
             order by ordinal_position
            """
        )
    )
    source_columns = {
        name: {"data_type": data_type, "udt_name": udt_name}
        for name, data_type, udt_name in source_column_rows
    }
    destination_column_rows = parse_tsv(
        mysql_query(
            f"""
            select column_name, is_nullable, coalesce(column_default, ''), extra
              from information_schema.columns
             where table_schema = database()
               and table_name = '{table}'
             order by ordinal_position
            """
        )
    )
    destination_columns = [row[0] for row in destination_column_rows]
    destination_column_metadata = {
        name: {"nullable": nullable, "default": default, "extra": extra}
        for name, nullable, default, extra in destination_column_rows
    }
    common_columns = [
        column
        for column in destination_columns
        if source_column_name(table, column) in source_columns
    ]

    missing_required = [
        name
        for name, nullable, default, extra in destination_column_rows
        if source_column_name(table, name) not in source_columns
        and nullable == "NO"
        and not default
        and "auto_increment" not in extra
    ]
    if missing_required:
        raise RuntimeError(
            f"{table} is missing required source columns: {', '.join(missing_required)}"
        )
    if not common_columns:
        raise RuntimeError(f"{table} has no common columns.")

    select_expressions = []
    for column in common_columns:
        source_column = source_column_name(table, column)
        quoted = quote_pg(source_column)
        qualified = f"source.{quoted}"
        data_type = source_columns[source_column]["data_type"]
        normalization = NULLABLE_REFERENCE_NORMALIZATIONS.get((table, column))
        if normalization:
            if destination_column_metadata[column]["nullable"] != "YES":
                raise RuntimeError(
                    f"{table}.{column} must be nullable before orphan normalization."
                )
            parent_table, parent_column = normalization
            parent_table_quoted = quote_pg(parent_table)
            parent_column_quoted = quote_pg(parent_column)
            expression = (
                f"case when {qualified} is null or exists ("
                f"select 1 from public.{parent_table_quoted} parent "
                f"where parent.{parent_column_quoted} = {qualified}"
                f") then {qualified} else null end"
            )
            orphan_count = int(
                pg_query(
                    f"""
                    select count(*)
                      from public.{quote_pg(table)} source
                      left join public.{parent_table_quoted} parent
                        on parent.{parent_column_quoted} = {qualified}
                     where {qualified} is not null
                       and parent.{parent_column_quoted} is null
                    """
                )
                or "0"
            )
            label = f"{table}.{column}"
            normalized_references[label] = orphan_count
            if orphan_count:
                print(
                    f"{label}: normalizing {orphan_count} orphan reference(s) to NULL"
                )
        elif data_type == "timestamp with time zone":
            expression = (
                f"case when {qualified} is null then null "
                f"else to_char({qualified} at time zone 'UTC', "
                "'YYYY-MM-DD HH24:MI:SS.US') end"
            )
        elif data_type == "timestamp without time zone":
            expression = (
                f"case when {qualified} is null then null "
                f"else to_char({qualified}, 'YYYY-MM-DD HH24:MI:SS.US') end"
            )
        elif data_type == "boolean":
            expression = (
                f"case when {qualified} is null then null "
                f"when {qualified} then '1' else '0' end"
            )
        else:
            expression = qualified
        select_expressions.append(expression)

    order_columns = [
        source_column_name(table, column)
        for column in ("id", "question_id", "category_key", "item_key", "job_name")
        if column in common_columns
    ]
    order_clause = (
        " order by "
        + ", ".join(f"source.{quote_pg(column)}" for column in order_columns)
        if order_columns
        else ""
    )
    select_sql = (
        f"select {', '.join(select_expressions)} "
        f"from public.{quote_pg(table)} source{order_clause}"
    )
    copy_sql = (
        f"copy ({select_sql}) to stdout "
        f"with (format csv, null '{NULL_MARKER}', encoding 'UTF8')"
    )
    exported = run(
        [
            "docker",
            "exec",
            POSTGRES_CONTAINER,
            "psql",
            "-X",
            "-q",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            PG_USER,
            "-d",
            PG_DATABASE,
            "-c",
            copy_sql,
        ],
        text=False,
    ).stdout
    csv_path = WORK_DIR / f"{table}.csv"
    csv_path.write_bytes(exported)
    container_path = f"/tmp/{table}.csv"
    run(["docker", "cp", str(csv_path), f"{MYSQL_CONTAINER}:{container_path}"], capture=False)

    variables = [f"@v{index}" for index in range(len(common_columns))]
    assignments = [
        f"{quote_mysql(column)} = nullif({variable}, '{NULL_MARKER}')"
        for column, variable in zip(common_columns, variables)
    ]
    load_sql = f"""
        load data local infile '{container_path}'
        into table {quote_mysql(table)}
        character set utf8mb4
        fields terminated by ',' optionally enclosed by '"' escaped by '"'
        lines terminated by '\\n'
        ({", ".join(variables)})
        set {", ".join(assignments)}
    """
    mysql_query("set foreign_key_checks = 0; " + load_sql + "; set foreign_key_checks = 1")

    source_count = int(pg_query(f"select count(*) from public.{quote_pg(table)}") or "0")
    destination_count = int(mysql_query(f"select count(*) from {quote_mysql(table)}") or "0")
    if source_count != destination_count:
        raise RuntimeError(
            f"{table} row count mismatch: PostgreSQL={source_count}, MySQL={destination_count}"
        )
    counts[table] = source_count

    auto_increment = any("auto_increment" in row[3] for row in destination_column_rows)
    if auto_increment and "id" in common_columns:
        maximum_id = int(mysql_query(f"select coalesce(max(id), 0) from {quote_mysql(table)}"))
        mysql_query(
            f"alter table {quote_mysql(table)} auto_increment = {maximum_id + 1}"
        )
    print(f"{table}: {source_count} rows")

orphan_checks = {
    "questions.user_id": ({"questions", "users"}, """
        select count(*) from questions q
        left join users u on u.id = q.user_id
        where q.user_id is not null and u.id is null
    """),
    "questions.study_id": ({"questions", "studies"}, """
        select count(*) from questions q
        left join studies s on s.id = q.study_id
        where q.study_id is not null and s.id is null
    """),
    "study_question_concepts.study_id": ({"study_question_concepts", "studies"}, """
        select count(*) from study_question_concepts c
        left join studies s on s.id = c.study_id
        where s.id is null
    """),
    "question_embeddings.question_id": ({"question_embeddings", "questions"}, """
        select count(*) from question_embeddings e
        left join questions q on q.id = e.question_id
        where q.id is null
    """),
    "user_term_agreements.terms_id": ({"user_term_agreements", "terms"}, """
        select count(*) from user_term_agreements a
        left join terms t on t.id = a.terms_id
        where t.id is null
    """),
    "user_memberships.user_id": ({"user_memberships", "users"}, """
        select count(*) from user_memberships m
        left join users u on u.id = m.user_id
        where u.id is null
    """),
    "user_avatar_items.user_id": ({"user_avatar_items", "users"}, """
        select count(*) from user_avatar_items i
        left join users u on u.id = i.user_id
        where u.id is null
    """),
    "user_avatar_items.item_key": ({"user_avatar_items", "avatar_items"}, """
        select count(*) from user_avatar_items i
        left join avatar_items a on a.item_key = i.item_key
        where a.item_key is null
    """),
}
for label, (required_tables, sql) in orphan_checks.items():
    if not required_tables.issubset(destination_tables):
        continue
    if int(mysql_query(sql) or "0") != 0:
        raise RuntimeError(f"Referential integrity failed: {label}")

summary_path = WORK_DIR / "migration-summary.json"
summary_path.write_text(
    json.dumps(
        {
            "source": "PostgreSQL",
            "destination": "MySQL",
            "tables": counts,
            "totalRows": sum(counts.values()),
            "normalizedReferences": normalized_references,
        },
        ensure_ascii=False,
        indent=2,
    )
)
print(f"Migration verified: {sum(counts.values())} rows across {len(counts)} tables.")
print(f"Summary: {summary_path}")
