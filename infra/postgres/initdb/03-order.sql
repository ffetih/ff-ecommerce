-- order-service: own database and user (ADR-0002). Local development credentials only.
CREATE USER order_service WITH PASSWORD 'order_service';
CREATE DATABASE order_db OWNER order_service;
