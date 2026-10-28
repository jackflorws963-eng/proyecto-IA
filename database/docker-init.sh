#!/bin/sh
# Primer arranque del contenedor PostgreSQL: usuario propio de la aplicacion (sin superusuario)
# y base de datos "smartstock". Flyway crea las tablas al iniciar el servidor.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -v clave="$DB_PASSWORD" <<'EOSQL'
CREATE ROLE smartstock LOGIN PASSWORD :'clave' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE smartstock OWNER smartstock;
EOSQL
