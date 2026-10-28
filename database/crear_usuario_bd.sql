-- =====================================================================
-- SmartStock IoT - Usuario propio de base de datos (ejecutar UNA vez como postgres)
-- El backend no debe conectarse como el superusuario "postgres": un superusuario
-- puede desactivar los triggers que protegen lecturas, movimientos y auditoria.
--
--   psql -U postgres -f database/crear_usuario_bd.sql
--
-- Cambie la contrasena antes de ejecutarlo y use la misma en DB_PASSWORD.
-- =====================================================================

CREATE ROLE smartstock LOGIN PASSWORD 'cambie-esta-clave' NOSUPERUSER NOCREATEDB NOCREATEROLE;

-- Base nueva cuyo duenio es el usuario de la aplicacion (Flyway crea ahi las tablas)
CREATE DATABASE smartstock OWNER smartstock;

-- Si la base "smartstock" YA existe y fue creada por postgres, en lugar de la linea
-- anterior ejecute, conectado a esa base (\c smartstock):
--   ALTER DATABASE smartstock OWNER TO smartstock;
--   ALTER SCHEMA public OWNER TO smartstock;
--   REASSIGN OWNED BY postgres TO smartstock;   -- solo si las tablas las creo postgres desde la app
