#!/bin/sh
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -v clave="$DB_PASSWORD" <<'EOSQL'
CREATE ROLE smartstock LOGIN PASSWORD :'clave' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE smartstock OWNER smartstock;
EOSQL
