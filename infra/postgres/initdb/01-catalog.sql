-- catalog-service: own database and user (ADR-0002). Local development credentials only.
CREATE USER catalog_service WITH PASSWORD 'catalog_service';
CREATE DATABASE catalog_db OWNER catalog_service;
