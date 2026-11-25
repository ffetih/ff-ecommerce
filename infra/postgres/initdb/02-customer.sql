-- customer-service: own database and user (ADR-0002). Local development credentials only.
CREATE USER customer_service WITH PASSWORD 'customer_service';
CREATE DATABASE customer_db OWNER customer_service;
